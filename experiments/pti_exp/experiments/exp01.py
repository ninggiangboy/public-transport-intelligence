"""EXP-01: kill the consumer mid-chunk; recover without loss or duplicates (DOC-45 EXP-01)."""

from __future__ import annotations

import json
import random
import statistics
import time
from datetime import datetime, timedelta

import httpx

from pti_exp import truth
from pti_exp.alerts import Alerts
from pti_exp.compose import Compose, wait_ready
from pti_exp.config import EXP_ENV
from pti_exp.db import truncate_baseline
from pti_exp.metrics import Scrape
from pti_exp.runner import InvalidRun, Run, Sampler, drain, groups, iso, now
from pti_exp.sim import Simulator

EXPECTED_ALERTS = ["TargetDown", "ConsumerStopped", "ConsumerLagHigh", "EndToEndLatencyHigh", "ThroughputDrop",
                   "LatencyStageSlow"]
OUTAGE_ALERTS = ["CircuitBreakerOpen", "DatabaseBottleneck", "ConsumerPaused"]
FAULT_POINTS = ["before-process", "before-write", "after-write-before-commit", "after-commit-before-ack"]
GTFS_GROUP = "pti-etl-gtfs-rt"


def run(r: Run, variant: str, rng: random.Random) -> dict:
    p = r.params
    compose, sim = Compose(), Simulator(r.stack.sim_url, r.requested_by)
    alerts = Alerts(r.stack.alertmanager_url, r.stack.prometheus_url)
    baseline = variant == "kill-external" or variant == "control"
    services = ["etl-stream", "etl-stream-baseline", "source-simulator"]
    r.write_config([*services, "etl-batch", "pg-warehouse", "kafka"])

    truncate_baseline(r.stack)
    silences = alerts.silence(EXPECTED_ALERTS + (OUTAGE_ALERTS if variant == "db-outage" else []),
                              timedelta(minutes=20), f"{r.exp}/{r.run_id}")
    sampler = Sampler(r.stack, groups(True), services).start()
    try:
        sim.rate(gtfs_rt=p["rate"], ticketing=1.0)
        time.sleep(p["settle"].total_seconds())
        t0 = now()
        before = Scrape.of(r.stack.actuator("etl-stream"))
        u = rng.uniform(p["fault_from"].total_seconds(), p["fault_to"].total_seconds())
        time.sleep(u)
        fault = {"kind": variant, "at_offset_s": round(u, 1)}
        recovery: dict = {}
        if variant in ("kill-external", "halt") or variant.startswith("halt-"):
            recovery, fault = _kill(r, variant, compose, sampler, rng, fault, before)
        elif variant == "db-outage":
            recovery = _outage(r, sampler)
        remaining = p["window"].total_seconds() - (now() - t0).total_seconds()
        if remaining > 0:
            time.sleep(remaining)
        sim.rate(gtfs_rt=0, ticketing=0)
        t1 = now()
        drain(groups(True), sampler=sampler)
        after = Scrape.of(r.stack.actuator("etl-stream"))
    finally:
        sampler.stop()
        alerts.unsilence(silences)
        sim.rate(gtfs_rt=1.0, ticketing=1.0)
    r.write_timeseries(sampler.rows)

    result = truth.correctness(r.stack, t0, t1, baseline)
    duplicates = after.sum("pti_etl_records_total", outcome="duplicate", source="~GTFS_RT_.*")
    if fault.get("counted_before") is not None:
        duplicates += fault.pop("counted_before")
    else:
        duplicates -= before.sum("pti_etl_records_total", outcome="duplicate", source="~GTFS_RT_.*")
    fired = alerts.fired(t0, t1 + timedelta(minutes=2))
    r.summary = {
        "window": {"t0": iso(t0), "t1": iso(t1)},
        "fault": fault,
        "normal": result["normal"],
        "baseline": result["baseline"],
        "recovery": recovery,
        "redelivered": duplicates,
        "no_inflight": variant == "kill-external" and duplicates == 0,
        "alerts": {"fired": fired,
                   "unexpected_fired": [a for a in fired if a not in EXPECTED_ALERTS + OUTAGE_ALERTS]},
    }
    return r.summary


def _kill(r: Run, variant: str, compose: Compose, sampler: Sampler, rng: random.Random, fault: dict,
          before: Scrape) -> tuple[dict, dict]:
    lag_ref = _mean_lag(sampler, 30)
    counted = Scrape.of(r.stack.actuator("etl-stream")).sum(
        "pti_etl_records_total", outcome="duplicate", source="~GTFS_RT_.*") - before.sum(
        "pti_etl_records_total", outcome="duplicate", source="~GTFS_RT_.*")
    if variant == "kill-external":
        t_kill = compose.kill("etl-stream", "etl-stream-baseline")
        time.sleep(r.params["restart_after"].total_seconds())
        compose.start("etl-stream", "etl-stream-baseline")
        fault = fault | {"target": "etl-stream,etl-stream-baseline"}
    else:
        point = variant.removeprefix("halt-") if variant.startswith("halt-") else rng.choice(FAULT_POINTS)
        polls = _polls_per_second(r)
        n = max(1, round(fault["at_offset_s"] * polls))
        compose.up("etl-stream", env={
            "PTI_ETL_EXTRA_PROFILES": ",experiment",
            "PTI_ETL_APPLICATION_JSON": json.dumps({"pti": {"test": {"fault": {point: "halt", "after-n": n}}}}),
            **EXP_ENV})
        deadline = time.monotonic() + 900
        while compose.running("etl-stream"):
            if time.monotonic() > deadline:
                raise InvalidRun("halt: the process did not exit within the window (N too large)")
            time.sleep(0.5)
        t_kill = now()
        compose.up("etl-stream", env=EXP_ENV)
        fault = fault | {"target": "etl-stream", "point": point, "after_n": n}
    t_start = compose.started_at("etl-stream")
    t_ready = wait_ready(r.stack.actuator("etl-stream"))
    caught_up = _caught_up(sampler, t_start, lag_ref)
    recovery = {"lag_ref": lag_ref, "t_kill": iso(t_kill), "t_start": iso(t_start), "t_ready": iso(t_ready),
                "t_caught_up": iso(caught_up),
                "recovery_seconds": (caught_up - t_start).total_seconds() if caught_up else None,
                "kill_to_caught_up": (caught_up - t_kill).total_seconds() if caught_up else None,
                "start_to_ready": (t_ready - t_start).total_seconds()}
    return recovery, fault | {"counted_before": counted}


def _outage(r: Run, sampler: Sampler) -> dict:
    proxy = r.stack.toxiproxy_url + "/proxies/pg-warehouse"
    lag_ref = _mean_lag(sampler, 30)
    started_before = Compose().started_at("etl-stream")
    httpx.post(proxy, json={"enabled": False}, timeout=5).raise_for_status()
    time.sleep(r.params["outage"].total_seconds())
    httpx.post(proxy, json={"enabled": True}, timeout=5).raise_for_status()
    enabled = now()
    caught_up = _caught_up(sampler, enabled, lag_ref)
    restarted = Compose().started_at("etl-stream") != started_before
    return {"lag_ref": lag_ref, "t_proxy_enabled": iso(enabled), "t_caught_up": iso(caught_up),
            "outage_to_caught_up": (caught_up - enabled).total_seconds() if caught_up else None,
            "restarted": restarted}


def _mean_lag(sampler: Sampler, seconds: int) -> float:
    values = [row[f"lag:{GTFS_GROUP}"] for row in sampler.rows[-(seconds // 2):] if f"lag:{GTFS_GROUP}" in row]
    return statistics.fmean(values) if values else 0.0


def _caught_up(sampler: Sampler, after, lag_ref: float, hold: float = 10, timeout: float = 600):
    """First time after `after` that the committed lag stays ≤ max(1.1 × lag_ref, 500) for `hold` seconds."""
    limit = max(1.1 * lag_ref, 500)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        since = None
        for row in sampler.rows:
            t = row["t"]
            if t < iso(after) or f"lag:{GTFS_GROUP}" not in row:
                continue
            if row[f"lag:{GTFS_GROUP}"] <= limit:
                since = since or t
                if _seconds(since, t) >= hold:
                    return datetime.fromisoformat(since.replace("Z", "+00:00"))
            else:
                since = None
        time.sleep(2)
    return None


def _seconds(a: str, b: str) -> float:
    return (datetime.fromisoformat(b.replace("Z", "+00:00")) - datetime.fromisoformat(a.replace("Z", "+00:00"))
            ).total_seconds()


def _polls_per_second(r: Run) -> float:
    first = Scrape.of(r.stack.actuator("etl-stream")).sum("pti_etl_stream_batches_total", source="~GTFS_RT_.*")
    time.sleep(10)
    second = Scrape.of(r.stack.actuator("etl-stream")).sum("pti_etl_stream_batches_total", source="~GTFS_RT_.*")
    return max(0.1, (second - first) / 10)


def criteria(summary: dict, variant: str) -> list[str]:
    """C1, C2 of EXP-01 §7 (and C4's no-restart for db-outage); the statistical ones are left to analyze."""
    problems = truth.violations(summary["normal"])
    if variant == "db-outage" and summary["recovery"].get("restarted"):
        problems.append("a process restarted during the outage")
    if variant == "control":
        for name, table in summary["baseline"].items():
            if table.get("lost"):
                problems.append(f"control: baseline {name} lost {table['lost']}")
    return problems
