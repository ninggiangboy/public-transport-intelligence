"""EXP-04: rebuild the warehouse from the raw zone and match the live one (DOC-45 EXP-04).

`full` follows EXP-04 §5 from `make reset`. `smoke` (DOC-45 §1.3) rebuilds a window of earlier runs of the chain:
it compares the fact rows and the dead letters of that window, keyed by business key, before and after
`make reset-warehouse` and a replay.
"""

from __future__ import annotations

import hashlib
import os
import subprocess
import time
from collections import Counter
from datetime import datetime, timedelta

from pti_exp import truth, warehouse
from pti_exp.compose import Compose, wait_ready
from pti_exp.config import EXP_ENV, REPO, Stack
from pti_exp.db import connect
from pti_exp.runner import InvalidRun, Run, drain, groups, iso, now
from pti_exp.sim import Simulator

GTFS_TABLES = ("dim_agency", "dim_route", "dim_stop", "gtfs_calendar", "gtfs_calendar_date", "gtfs_shape",
               "gtfs_stop_time", "gtfs_trip", "route_headway")
SOURCES = ("TICKETING_SALES", "GTFS_RT_VEHICLE_POSITION", "GTFS_RT_TRIP_UPDATE")
# Replay skips DQ-12 (DR-16, RuleContext): its dead letters are written as facts again, by design.
REPLAY_SKIPPED_RULES = {"DQ-12"}
# A trip-update row keeps what earlier messages observed (DR-13): only keys whose whole history is replayed match.
TRIP_HISTORY = timedelta(hours=4)
EDGE = timedelta(seconds=5)
# A sale's row changes for at most 5 minutes after it is created (void ≤ 5 s, delete ≤ 5 min, DOC-25 §9.3).
TICKET_SETTLE = timedelta(minutes=6)


def checksum(fingerprints: dict[str, str]) -> dict:
    digest = hashlib.md5("\n".join(fingerprints[k] for k in sorted(fingerprints)).encode(), usedforsecurity=False)
    return {"row_count": len(fingerprints), "checksum": digest.hexdigest()}


def snapshot(stack: Stack, w0: datetime, w1: datetime, until: datetime) -> dict:
    """The state of the window: fact rows by key and the dead letters by position."""
    exp = truth.expected(stack, w0, w1)
    later = truth.expected(stack, w1, until)
    vp_keys = sorted(exp.keys["VEHICLE_POSITION"])
    # A trip-update key touched after the window, or before the replay starts, differs after a replay of the window.
    tu_keys = sorted(set(exp.keys["TRIP_UPDATE"]) - set(later.keys["TRIP_UPDATE"])
                     - trip_keys(stack, w0 - timedelta(minutes=1) - TRIP_HISTORY, w0 - timedelta(minutes=1) + EDGE))
    offset = stack.clock_offset
    b0, b1 = w0 + offset, w1 + offset - TICKET_SETTLE
    with connect(stack, "ticketing_source") as c:
        sales = [str(r[0]) for r in c.execute(
            "SELECT transaction_id FROM public.ticket_transaction WHERE created_at >= %s AND created_at < %s",
            (b0, b1))]
    state = {
        "fact_vehicle_position": warehouse.fingerprints(stack, "fact_vehicle_position", vp_keys),
        "fact_trip_update": warehouse.fingerprints(stack, "fact_trip_update", tu_keys),
        "fact_ticket_sales": warehouse.fingerprints(stack, "fact_ticket_sales", sales),
    }
    dlq = {f"{t}|{p}|{o}": f"{stage}|{rule}" for t, p, o, stage, rule in truth.dead_letters(stack, w0, w1)
           if rule not in REPLAY_SKIPPED_RULES}
    return {"tables": state, "dlq": dlq, "keys": {"vp": len(vp_keys), "tu": len(tu_keys), "sales": len(sales)}}


def trip_keys(stack: Stack, start: datetime, end: datetime) -> set[str]:
    with connect(stack, "pti_sim") as c:
        return {r[0] for r in c.execute(
            "SELECT DISTINCT unnest(business_keys) FROM sim.sim_ledger WHERE entity_type = 'TRIP_UPDATE' "
            "AND produced_at >= %s AND produced_at < %s", (start, end))}


def gtfs_counts(stack: Stack) -> dict[str, int]:
    """Rows of the ACTIVE feed per GTFS table (C4)."""
    with connect(stack, "pti_warehouse") as c:
        return {t: c.execute(f"SELECT count(*) FROM dw.{t}_current").fetchone()[0] for t in GTFS_TABLES}


def compare(before: dict, after: dict) -> dict:
    tables = {}
    for table, fp in before["tables"].items():
        other = after["tables"][table]
        missing = [k for k in fp if k not in other]
        different = [k for k in fp if k in other and other[k] != fp[k]]
        tables[table] = {"before": checksum(fp), "after": checksum({k: other[k] for k in fp if k in other}),
                         "table_match": not missing and not different, "missing": len(missing),
                         "different": len(different), "sample": (missing + different)[:20]}
    dlq_before, dlq_after = set(before["dlq"].items()), set(after["dlq"].items())
    return {"tables": tables, "dlq_symdiff": len(dlq_before ^ dlq_after), "dlq_before": len(dlq_before),
            "dlq_missing": dict(Counter(v for _, v in dlq_before - dlq_after)),
            "dlq_extra": dict(Counter(v for _, v in dlq_after - dlq_before))}


def make(target: str, **variables: str) -> str:
    env = {**os.environ, **{k: v for k, v in variables.items() if k.startswith("PTI_")}}
    args = ["make", "-C", str(REPO), target] + [f"{k}={v}" for k, v in variables.items() if not k.startswith("PTI_")]
    done = subprocess.run(args, capture_output=True, text=True, env=env)
    if done.returncode != 0:
        raise InvalidRun(f"make {target} failed: {done.stdout[-500:]} {done.stderr[-500:]}")
    return done.stdout


def active_feed(stack: Stack) -> str | None:
    with connect(stack, "pti_warehouse") as c:
        row = c.execute("SELECT feed_hash FROM dw.gtfs_feed_version WHERE status = 'ACTIVE'").fetchone()
        return row[0] if row else None


def rebuild(r: Run, w0: datetime, w1: datetime, feed_hash: str) -> dict:
    """make reset-warehouse with the feed from the raw zone, then the four replays of EXP-04 §5 step 7."""
    started = now()
    make("reset-warehouse", PTI_GTFS_BOOTSTRAP_LOCATION=f"s3://raw/gtfs-static/{feed_hash}.zip", **EXP_ENV)
    reset_seconds = (now() - started).total_seconds()
    deadline = time.monotonic() + 600
    while active_feed(r.stack) is None:
        if time.monotonic() > deadline:
            raise InvalidRun("the GTFS feed was not loaded again within 10 minutes")
        time.sleep(3)
    gtfs_seconds = (now() - started).total_seconds() - reset_seconds
    wait_ready(r.stack.actuator("etl-stream"))
    frm, to = iso(w0 - timedelta(minutes=1)), iso(w1 + timedelta(minutes=1))
    stats = {"TICKETING_SALE_POINTS": make("replay", SOURCE="TICKETING_SALE_POINTS", FROM=frm, TO=to, WAIT="1")}
    procs = {s: subprocess.Popen(["make", "-C", str(REPO), "replay", f"SOURCE={s}", f"FROM={frm}", f"TO={to}",
                                  "WAIT=1"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
             for s in SOURCES}
    for source, proc in procs.items():
        out, err = proc.communicate(timeout=3600)
        if proc.returncode != 0:
            raise InvalidRun(f"replay of {source} failed: {out[-300:]} {err[-300:]}")
        stats[source] = out.strip().splitlines()[-1]
    return {"reset_seconds": reset_seconds, "gtfs_load_seconds": gtfs_seconds,
            "rebuild_total_seconds": (now() - started).total_seconds(), "replay_stats": stats}


def run_window(r: Run, w0: datetime, w1: datetime) -> dict:
    """The smoke variant: rebuild [w0, w1] of earlier runs (DOC-45 §1.3)."""
    compose, sim = Compose(), Simulator(r.stack.sim_url, r.requested_by)
    r.write_config(["etl-stream", "etl-batch", "source-simulator"])
    if (now() - w1) < timedelta(minutes=10):
        raise InvalidRun("the window is younger than the raw-zone settle time (DR-70)")
    sim.rate(gtfs_rt=0, ticketing=0)
    compose.stop("source-simulator")
    try:
        stopped = now()
        drain(groups(False))
        feed_hash = active_feed(r.stack)
        if feed_hash is None:
            raise InvalidRun("no ACTIVE feed before the rebuild")
        before, gtfs_before = snapshot(r.stack, w0, w1, stopped), gtfs_counts(r.stack)
        rebuilt = rebuild(r, w0, w1, feed_hash)
        drain(groups(False))
        after, gtfs_after = snapshot(r.stack, w0, w1, stopped), gtfs_counts(r.stack)
    finally:
        compose.start("source-simulator")
        wait_ready(r.stack.actuator("source-simulator"))
    result = compare(before, after)
    r.summary = {"window": {"t0": iso(w0), "t1": iso(w1)}, "keys": before["keys"], **result, **rebuilt,
                 "gtfs_rows": {"before": gtfs_before, "after": gtfs_after},
                 "gtfs_rows_match": gtfs_before == gtfs_after}
    return r.summary


def run_full(r: Run) -> dict:
    """EXP-04 §5 from an empty stack: 30 minutes of load with noise, then the rebuild, twice (P3-10)."""
    p = r.params
    make("reset")
    make("up-exp")
    sim = Simulator(r.stack.sim_url, r.requested_by)
    sim.rate(gtfs_rt=p["rate"], ticketing=1.0)
    t_stack = now()
    kinds = ["malformed_json", "schema_violation", "unknown_schema_version", "out_of_bbox", "unknown_route",
             "unknown_stop", "future_timestamp", "delay_out_of_range"]
    load = f"PT{int(p['load'].total_seconds())}S"
    sim.start("bad-data", {"ratio": p["bad_ratio"], "kinds": kinds, "duration": load})
    sim.start("duplicates", {"ratio": p["dup_ratio"], "duration": load})
    time.sleep(p["load"].total_seconds() + 90)
    Compose().stop("source-simulator")
    drain(groups(True))
    t_stop = now()
    time.sleep(p["settle_raw"].total_seconds())
    feed_hash = active_feed(r.stack)
    before, gtfs_before = snapshot(r.stack, t_stack, t_stop, t_stop), gtfs_counts(r.stack)
    first = rebuild(r, t_stack, t_stop, feed_hash)
    after = snapshot(r.stack, t_stack, t_stop, t_stop)
    result = compare(before, after)
    result["gtfs_rows_match"] = gtfs_before == gtfs_counts(r.stack)
    if p["rebuild_twice"]:
        rebuild(r, t_stack, t_stop, feed_hash)
        again = snapshot(r.stack, t_stack, t_stop, t_stop)
        result["idempotent"] = {t: checksum(after["tables"][t]) == checksum(again["tables"][t])
                                for t in after["tables"]}
    Compose().start("source-simulator")
    r.summary = {"window": {"t0": iso(t_stack), "t1": iso(t_stop)}, **result, **first}
    return r.summary


def criteria(summary: dict) -> list[str]:
    """C1, C2, C4 (and C3 when rebuilt twice) of EXP-04 §7, over the compared tables."""
    problems = [f"C1: {t} does not match ({v['missing']} missing, {v['different']} different)"
                for t, v in summary["tables"].items() if not v["table_match"]]
    if summary["dlq_symdiff"]:
        problems.append(f"C2: dlq_symdiff = {summary['dlq_symdiff']}")
    if not summary.get("gtfs_rows_match"):
        problems.append("C4: the GTFS row counts differ")
    for table, same in (summary.get("idempotent") or {}).items():
        if not same:
            problems.append(f"C3: {table} differs after the second rebuild")
    return problems
