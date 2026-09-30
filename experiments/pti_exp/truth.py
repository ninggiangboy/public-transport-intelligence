"""Ground truth against the warehouse (DOC-45 §3, §4.1): expected keys from the ledger, rows found for them in the
normal tables and in the baseline shadow tables, dead letters of valid messages, and `latest` regressions."""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass, field
from datetime import datetime, timedelta

from pti_exp.config import Stack
from pti_exp.db import connect

VEHICLE_POSITION = "VEHICLE_POSITION"
TRIP_UPDATE = "TRIP_UPDATE"
TABLES = {VEHICLE_POSITION: "fact_vehicle_position", TRIP_UPDATE: "fact_trip_update"}
ENTITY_KEY = {VEHICLE_POSITION: "GTFS_RT_VEHICLE_POSITION", TRIP_UPDATE: "GTFS_RT_TRIP_UPDATE"}

EXPECTED = """
WITH msgs AS (
  SELECT unnest(business_keys) AS bkey, entity_type, event_timestamp, payload_hash, produced_at
  FROM sim.sim_ledger
  WHERE produced_at >= %(t0)s AND produced_at < %(t1)s AND NOT intended_invalid
)
SELECT DISTINCT ON (bkey) bkey, entity_type, payload_hash
FROM msgs
ORDER BY bkey, event_timestamp DESC, produced_at DESC
"""


@dataclass
class Expected:
    """bkey → expected payload hash, by entity type."""

    keys: dict[str, dict[str, str]] = field(default_factory=lambda: {VEHICLE_POSITION: {}, TRIP_UPDATE: {}})

    def count(self, entity: str) -> int:
        return len(self.keys[entity])


def expected(stack: Stack, t0: datetime, t1: datetime) -> Expected:
    result = Expected()
    with connect(stack, "pti_sim") as c:
        for bkey, entity, payload_hash in c.execute(EXPECTED, {"t0": t0, "t1": t1}):
            result.keys[entity][bkey] = payload_hash
    return result


def _vp_parts(keys):
    vehicles, times = [], []
    for k in keys:
        vehicle, ts = k.split("|", 1)
        vehicles.append(vehicle)
        times.append(ts)
    return vehicles, times


def actual(stack: Stack, entity: str, keys: list[str], schema: str = "dw") -> dict[str, list[str]]:
    """bkey → payload hashes of every row with that key (the baseline keeps duplicates)."""
    if not keys:
        return {}
    table = f"{schema}.{'exp_' if schema == 'exp' else ''}{TABLES[entity]}"
    rows: dict[str, list[str]] = {}
    with connect(stack, "pti_warehouse") as c:
        if entity == VEHICLE_POSITION:
            vehicles, times = _vp_parts(keys)
            query = f"""
                SELECT k.v || '|' || k.t, f.payload_hash
                FROM unnest(%s::text[], %s::text[]) AS k(v, t)
                JOIN {table} f ON f.vehicle_id = k.v AND f.event_timestamp = k.t::timestamptz
                WHERE f.event_timestamp BETWEEN %s AND %s
            """
            low, high = min(times), max(times)
            cursor = c.execute(query, (vehicles, times, low, high))
        else:
            dates, trips, seqs = [], [], []
            for k in keys:
                d, trip, seq = k.split("|")
                dates.append(d)
                trips.append(trip)
                seqs.append(int(seq))
            query = f"""
                SELECT k.d || '|' || k.trip || '|' || k.seq, f.payload_hash
                FROM unnest(%s::date[], %s::text[], %s::int[]) AS k(d, trip, seq)
                JOIN {table} f ON f.service_date = k.d AND f.trip_id = k.trip AND f.stop_sequence = k.seq
                WHERE f.service_date BETWEEN %s AND %s
            """
            cursor = c.execute(query, (dates, trips, seqs, min(dates), max(dates)))
        for bkey, payload_hash in cursor:
            rows.setdefault(bkey, []).append(payload_hash)
    return rows


@dataclass
class Correctness:
    expected: int
    lost: int
    duplicates: int
    wrong_value: int
    lost_keys: list[str]
    wrong_keys: list[str]

    def as_dict(self) -> dict:
        return {"expected": self.expected, "lost": self.lost, "duplicates": self.duplicates,
                "wrong_value": self.wrong_value, "loss_rate": self.lost / self.expected if self.expected else 0.0,
                "dup_rate": self.duplicates / self.expected if self.expected else 0.0}


def compare(expected_keys: dict[str, str], rows: dict[str, list[str]]) -> Correctness:
    lost = [k for k in expected_keys if k not in rows]
    duplicates = sum(len(v) - 1 for k, v in rows.items() if k in expected_keys and len(v) > 1)
    wrong = [k for k, want in expected_keys.items() if k in rows and want not in rows[k]]
    # In the normal tables there is one row per key; "wrong" means that row carries another message's hash.
    wrong = [k for k in wrong if len(rows[k]) == 1] + [k for k in wrong if len(rows[k]) > 1]
    return Correctness(len(expected_keys), len(lost), duplicates, len(wrong), lost[:50], wrong[:50])


def valid_positions(stack: Stack, t0: datetime, t1: datetime) -> set[tuple[str, int, int]]:
    with connect(stack, "pti_sim") as c:
        return set(c.execute(
            "SELECT kafka_topic, kafka_partition, kafka_offset FROM sim.sim_ledger "
            "WHERE produced_at >= %s AND produced_at < %s AND NOT intended_invalid", (t0, t1)).fetchall())


def invalid_messages(stack: Stack, t0: datetime, t1: datetime) -> dict[tuple[str, int, int], dict]:
    """Position → {invalid_kind, entity_type, business_keys} of the deliberately corrupted messages."""
    with connect(stack, "pti_sim") as c:
        rows = c.execute(
            "SELECT kafka_topic, kafka_partition, kafka_offset, invalid_kind, entity_type, business_keys "
            "FROM sim.sim_ledger WHERE produced_at >= %s AND produced_at < %s AND intended_invalid", (t0, t1))
        return {(t, p, o): {"kind": k, "entity": e, "keys": list(keys)} for t, p, o, k, e, keys in rows}


def dead_letters(stack: Stack, t0: datetime, t1: datetime) -> list[tuple[str, int, int, str, str | None]]:
    """(topic, partition, offset, stage, rule_id) of GTFS-rt dead letters whose record time is in the window."""
    with connect(stack, "pti_warehouse") as c:
        return c.execute(
            "SELECT kafka_topic, kafka_partition, kafka_offset, stage, rule_id FROM ops.dead_letter "
            "WHERE kafka_topic LIKE 'gtfs.%%' AND kafka_timestamp >= %s AND kafka_timestamp < %s",
            (t0 - timedelta(seconds=5), t1 + timedelta(seconds=5))).fetchall()


def unexpected_dead_letters(stack: Stack, t0: datetime, t1: datetime) -> int:
    valid = valid_positions(stack, t0, t1)
    return sum(1 for t, p, o, _, _ in dead_letters(stack, t0, t1) if (t, p, o) in valid)


def latest_regressions(stack: Stack, since: datetime) -> int:
    with connect(stack, "pti_warehouse") as c:
        return c.execute("""
            SELECT count(*) FROM dw.vehicle_position_latest l
            JOIN (SELECT vehicle_id, max(event_timestamp) AS m FROM dw.fact_vehicle_position
                  WHERE event_timestamp >= %s GROUP BY 1) f USING (vehicle_id)
            WHERE l.event_timestamp < f.m
        """, (since,)).fetchone()[0]


def ticketing(stack: Stack, business_t0: datetime, business_t1: datetime) -> dict:
    """Sales created in the window (business time) that are missing from fact_ticket_sales (DR-28: the source DB is
    the truth for ticketing)."""
    with connect(stack, "ticketing_source") as c:
        ids = [str(r[0]) for r in c.execute(
            "SELECT transaction_id FROM public.ticket_transaction WHERE created_at >= %s AND created_at < %s",
            (business_t0, business_t1))]
    if not ids:
        return {"expected": 0, "lost": 0, "duplicates": 0, "wrong_value": 0}
    with connect(stack, "pti_warehouse") as c:
        found = Counter(str(r[0]) for r in c.execute(
            "SELECT transaction_id FROM dw.fact_ticket_sales WHERE transaction_id = ANY(%s::uuid[])", (ids,)))
    lost = [i for i in ids if i not in found]
    return {"expected": len(ids), "lost": len(lost), "duplicates": sum(v - 1 for v in found.values()),
            "wrong_value": 0, "lost_keys": lost[:50]}


def resend_keys(stack: Stack, t0: datetime, t1: datetime) -> dict[str, set[str]]:
    result: dict[str, set[str]] = {VEHICLE_POSITION: set(), TRIP_UPDATE: set()}
    with connect(stack, "pti_sim") as c:
        for entity, keys in c.execute(
                "SELECT entity_type, business_keys FROM sim.sim_ledger "
                "WHERE produced_at >= %s AND produced_at < %s AND is_resend", (t0, t1)):
            result[entity].update(keys)
    return result


def resend_count(stack: Stack, t0: datetime, t1: datetime) -> dict[str, int]:
    with connect(stack, "pti_sim") as c:
        return dict(c.execute(
            "SELECT entity_type, count(*) FROM sim.sim_ledger WHERE produced_at >= %s AND produced_at < %s "
            "AND is_resend GROUP BY 1", (t0, t1)).fetchall())


def correctness(stack: Stack, t0: datetime, t1: datetime, baseline: bool) -> dict:
    """README §4.1 for both GTFS-rt tables, in normal mode and (if it ran) in baseline mode."""
    exp = expected(stack, t0, t1)
    normal, base = {}, {}
    for entity in (VEHICLE_POSITION, TRIP_UPDATE):
        keys = list(exp.keys[entity])
        result = compare(exp.keys[entity], actual(stack, entity, keys))
        normal[ENTITY_KEY[entity]] = result.as_dict() | {"lost_keys": result.lost_keys,
                                                         "wrong_keys": result.wrong_keys}
        if baseline:
            b = compare(exp.keys[entity], actual(stack, entity, keys, schema="exp"))
            base[ENTITY_KEY[entity]] = b.as_dict()
    offset = stack.clock_offset
    normal["TICKETING_SALES"] = ticketing(stack, t0 + offset, t1 + offset)
    earliest = t0 - timedelta(hours=2)
    normal["unexpected_dlq"] = unexpected_dead_letters(stack, t0, t1)
    normal["latest_regressions"] = latest_regressions(stack, earliest)
    return {"normal": normal, "baseline": base}


def violations(summary_normal: dict) -> list[str]:
    """What breaks "no loss, no duplicate" (README §4.1): empty when the run passes C1."""
    problems = []
    for name, table in summary_normal.items():
        if isinstance(table, dict):
            for metric in ("lost", "wrong_value", "duplicates"):
                if table.get(metric):
                    problems.append(f"{name}.{metric}={table[metric]}")
    for metric in ("unexpected_dlq", "latest_regressions"):
        if summary_normal.get(metric):
            problems.append(f"{metric}={summary_normal[metric]}")
    return problems
