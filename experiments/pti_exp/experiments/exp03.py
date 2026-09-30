"""EXP-03: bad records do not cost valid ones (DOC-45 EXP-03)."""

from __future__ import annotations

import time
from collections import Counter
from datetime import timedelta

from pti_exp import truth
from pti_exp.alerts import Alerts
from pti_exp.db import truncate_baseline
from pti_exp.metrics import Scrape, delta_buckets, quantile
from pti_exp.runner import Run, Sampler, drain, groups, iso, now, quiesce
from pti_exp.sim import Simulator

EXPECTED_ALERTS = ["DlqRateHigh", "DlqBacklogHigh", "DataQualityCheckFailed"]
STAGES = {"malformed_json": ("DESERIALIZE", {None}), "schema_violation": ("SCHEMA", {"DQ-01"}),
          "unknown_schema_version": ("SCHEMA", {"DQ-01"}), "out_of_bbox": ("QUALITY", {"DQ-06"}),
          "unknown_route": ("QUALITY", {"DQ-03"}), "unknown_stop": ("QUALITY", {"DQ-04"}),
          "future_timestamp": ("QUALITY", {"DQ-07"}), "delay_out_of_range": ("QUALITY", {"DQ-08"})}
GTFS = "~GTFS_RT_.*"


def run(r: Run) -> dict:
    p = r.params
    sim, alerts = Simulator(r.stack.sim_url, r.requested_by), Alerts(r.stack.alertmanager_url, r.stack.prometheus_url)
    r.write_config(["etl-stream", "etl-stream-baseline", "source-simulator"])
    truncate_baseline(r.stack)
    silences = alerts.silence(EXPECTED_ALERTS, timedelta(minutes=25), f"{r.exp}/{r.run_id}")
    sampler = Sampler(r.stack, groups(True), ["etl-stream", "etl-stream-baseline"]).start()
    stalls_before = _stall_state(r)
    try:
        sim.rate(gtfs_rt=1.0, ticketing=1.0)
        time.sleep(p["settle"].total_seconds())
        t0 = now()
        before = Scrape.of(r.stack.actuator("etl-stream"))
        if p["ratio"] > 0:
            sim.start("bad-data", {"ratio": p["ratio"], "duration": f"PT{int(p['duration'].total_seconds())}S"})
        time.sleep(p["duration"].total_seconds())
        stall_samples = _stall_state(r)
        t1 = quiesce(sim)
        drain(groups(True), sampler=sampler)
        after = Scrape.of(r.stack.actuator("etl-stream"))
    finally:
        sampler.stop()
        alerts.unsilence(silences)
    r.write_timeseries(sampler.rows)

    result = truth.correctness(r.stack, t0, t1, baseline=True)
    invalid = truth.invalid_messages(r.stack, t0, t1)
    dlq = truth.dead_letters(r.stack, t0, t1)
    by_position = Counter((t, pa, o) for t, pa, o, _, _ in dlq)
    rows = {(t, pa, o): (stage, rule) for t, pa, o, stage, rule in dlq}
    per_kind: dict[str, dict] = {}
    for position, message in invalid.items():
        kind = per_kind.setdefault(message["kind"], {"sent": 0, "in_dlq": 0, "stage_ok": 0, "rule_ok": 0})
        kind["sent"] += 1
        if position in rows:
            kind["in_dlq"] += 1
            stage, rule = rows[position]
            want_stage, want_rules = STAGES[message["kind"]]
            kind["stage_ok"] += stage == want_stage
            kind["rule_ok"] += rule in want_rules
    sent = sum(k["sent"] for k in per_kind.values())
    in_dlq = sum(k["in_dlq"] for k in per_kind.values())
    leaked = _leaked(r, [m for m in invalid.values() if m["entity"] == "VEHICLE_POSITION"])
    buckets = delta_buckets(before.buckets("pti_etl_kafka_to_commit_seconds", source=GTFS),
                            after.buckets("pti_etl_kafka_to_commit_seconds", source=GTFS))
    valid_loaded = {name: (t["expected"] - t["lost"]) / t["expected"] if t["expected"] else 1.0
                    for name, t in result["normal"].items() if isinstance(t, dict)}
    r.summary = {
        "window": {"t0": iso(t0), "t1": iso(t1)}, "ratio": p["ratio"],
        "normal": result["normal"], "baseline": result["baseline"],
        "valid_loaded_ratio": valid_loaded,
        "dlq_recall": in_dlq / sent if sent else 1.0,
        "dlq_multiplicity": sum(1 for n in by_position.values() if n > 1),
        "stage_accuracy": {k: v["stage_ok"] / v["in_dlq"] if v["in_dlq"] else None for k, v in per_kind.items()},
        "rule_accuracy": {k: v["rule_ok"] / v["in_dlq"] if v["in_dlq"] else None for k, v in per_kind.items()},
        "per_kind": per_kind, "invalid_sent": sent,
        "false_dlq": result["normal"]["unexpected_dlq"], "leaked": leaked,
        "stalls": _stalls(stalls_before, stall_samples, sampler.rows),
        "commit_p95": quantile(0.95, buckets),
        "alerts": {"fired": alerts.fired(t0, t1 + timedelta(minutes=1))},
    }
    return r.summary


def _leaked(r: Run, messages: list[dict]) -> int:
    keys = [k for m in messages for k in m["keys"]]
    return len(truth.actual(r.stack, "VEHICLE_POSITION", keys)) if keys else 0


def _stall_state(r: Run) -> dict:
    scrape = Scrape.of(r.stack.actuator("etl-stream"))
    return {"running_min": min((s.value for s in scrape.samples if s.name == "pti_etl_listener_running"), default=1),
            "paused_max": max((s.value for s in scrape.samples if s.name == "pti_etl_listener_paused"), default=0),
            "start": scrape.max("process_start_time_seconds")}


def _stalls(before: dict, during: dict, rows: list[dict]) -> int:
    stalls = int(during["running_min"] == 0) + int(during["paused_max"] == 1)
    stalls += int(before["start"] != during["start"])
    stalls += sum(1 for row in rows if row.get("state:etl-stream") == "down")
    return stalls


def criteria(summary: dict) -> list[str]:
    """C1–C5 of EXP-03 §7."""
    problems = [p for p in truth.violations(summary["normal"]) if ".lost=" not in p]
    for name, ratio in summary["valid_loaded_ratio"].items():
        if ratio < 1:
            problems.append(f"C1: valid_loaded_ratio[{name}] = {ratio}")
    if summary["invalid_sent"] and summary["dlq_recall"] < 1:
        problems.append(f"C2: dlq_recall = {summary['dlq_recall']}")
    if summary["dlq_multiplicity"]:
        problems.append(f"C2: dlq_multiplicity = {summary['dlq_multiplicity']}")
    for kind, accuracy in summary["stage_accuracy"].items():
        if accuracy is not None and accuracy < 1:
            problems.append(f"C3: stage_accuracy[{kind}] = {accuracy}")
    for kind, accuracy in summary["rule_accuracy"].items():
        if accuracy is not None and accuracy < 1:
            problems.append(f"C3: rule_accuracy[{kind}] = {accuracy}")
    if summary["leaked"]:
        problems.append(f"C4: leaked = {summary['leaked']}")
    if summary["stalls"]:
        problems.append(f"C5: stalls = {summary['stalls']}")
    return problems


