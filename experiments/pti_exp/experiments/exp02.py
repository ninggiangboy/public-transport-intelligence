"""EXP-02: redelivered messages create no duplicate rows (DOC-45 EXP-02), variant `short` (the `long` one runs in
P3-10 with the same code and a 65–75 minute delay)."""

from __future__ import annotations

import time
from datetime import timedelta

from pti_exp import truth
from pti_exp.alerts import Alerts
from pti_exp.db import truncate_baseline
from pti_exp.metrics import Scrape
from pti_exp.runner import InvalidRun, Run, Sampler, drain, groups, iso, now, quiesce
from pti_exp.sim import Simulator

GTFS = "~GTFS_RT_.*"


def run(r: Run, variant: str) -> dict:
    p = r.params
    sim, alerts = Simulator(r.stack.sim_url, r.requested_by), Alerts(r.stack.alertmanager_url, r.stack.prometheus_url)
    r.write_config(["etl-stream", "etl-stream-baseline", "source-simulator"])
    truncate_baseline(r.stack)
    sampler = Sampler(r.stack, groups(True), ["etl-stream", "etl-stream-baseline"]).start()
    try:
        sim.rate(gtfs_rt=1.0, ticketing=1.0)
        time.sleep(p["settle"].total_seconds())
        t0 = now()
        before = Scrape.of(r.stack.actuator("etl-stream"))
        sim_before = Scrape.of(r.stack.actuator("source-simulator"))
        delays = (p["min_delay"], p["max_delay"]) if variant == "short" else (65 * 60, 75 * 60)
        sim.start("duplicates", {"ratio": p["ratio"], "minDelay": _iso(delays[0]), "maxDelay": _iso(delays[1]),
                                 "duration": _iso(p["duration"])})
        time.sleep(p["duration"].total_seconds())
        deadline = time.monotonic() + (delays[1].total_seconds() if hasattr(delays[1], "total_seconds")
                                       else delays[1]) + 300
        while Scrape.of(r.stack.actuator("source-simulator")).sum("pti_sim_resend_queue_depth") > 0:
            if time.monotonic() > deadline:
                raise InvalidRun("the resend queue did not empty")
            time.sleep(2)
        t1 = quiesce(sim)
        drain(groups(True), sampler=sampler)
        after = Scrape.of(r.stack.actuator("etl-stream"))
        sim_after = Scrape.of(r.stack.actuator("source-simulator"))
    finally:
        sampler.stop()
    r.write_timeseries(sampler.rows)
    if sim_after.sum("pti_sim_emissions_skipped_total", reason="resend_queue_full") > sim_before.sum(
            "pti_sim_emissions_skipped_total", reason="resend_queue_full"):
        raise InvalidRun("the resend queue was full")

    result = truth.correctness(r.stack, t0, t1, baseline=True)
    resends = truth.resend_count(r.stack, t0, t1)
    resend_keys = truth.resend_keys(r.stack, t0, t1)

    def delta(name: str, **match: str) -> float:
        return after.sum(name, **match) - before.sum(name, **match)

    registry = delta("pti_etl_duplicates_total", mode="stream", reason="registry", source=GTFS)
    guard = delta("pti_etl_duplicates_total", mode="stream", reason="guard", source=GTFS)
    in_chunk = delta("pti_etl_duplicates_total", mode="stream", reason="in_chunk", source=GTFS)
    total = delta("pti_etl_records_total", mode="stream", outcome="duplicate", source=GTFS)
    exp = truth.expected(r.stack, t0, t1)
    per_table = {}
    for entity, table in truth.ENTITY_KEY.items():
        keys = {k: v for k, v in exp.keys[entity].items() if k in resend_keys[entity]}
        normal = truth.compare(keys, truth.actual(r.stack, entity, list(keys)))
        base = truth.compare(keys, truth.actual(r.stack, entity, list(keys), schema="exp"))
        per_table[table] = {"resends": resends.get(entity, 0), "resend_keys": len(keys), "lost_resend": normal.lost,
                            "wrong_resend": normal.wrong_value, "baseline_extra_rows": base.duplicates}
    all_resends = sum(resends.values())
    r.summary = {
        "window": {"t0": iso(t0), "t1": iso(t1)}, "variant": variant, "ratio": p["ratio"],
        "normal": result["normal"], "baseline": result["baseline"], "resend": per_table,
        "registry_hits": registry, "guard_blocks": guard, "in_chunk_collapses": in_chunk,
        "total_duplicate": total, "resends": all_resends,
        "baseline_extra_ratio": (sum(t["baseline_extra_rows"] for t in per_table.values()) / all_resends
                                 if all_resends else None),
        "alerts": {"unexpected_fired": alerts.fired(t0, t1 + timedelta(minutes=1))},
    }
    return r.summary


def _iso(value) -> str:
    seconds = int(value.total_seconds() if hasattr(value, "total_seconds") else value)
    return f"PT{seconds}S"


def criteria(summary: dict) -> list[str]:
    """C1, C2, C4 (short), C5 of EXP-02 §7."""
    problems = truth.violations(summary["normal"])
    resends = summary["resends"]
    if summary["variant"] == "short" and resends and \
            summary["registry_hits"] + summary["in_chunk_collapses"] < 0.99 * resends:
        problems.append(f"C4: registry {summary['registry_hits']} + in-chunk {summary['in_chunk_collapses']} "
                        f"< 99% of {resends} resends")
    if summary["total_duplicate"] != summary["registry_hits"] + summary["guard_blocks"] + summary["in_chunk_collapses"]:
        problems.append("C5: total_duplicate does not equal registry + guard + in-chunk")
    return problems
