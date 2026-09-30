"""EXP-05: the load at which NFR-03 still holds on compose (DOC-45 EXP-05), series `etl-only` before P4."""

from __future__ import annotations

import statistics
import threading
import time
from datetime import datetime, timedelta

from pti_exp import truth
from pti_exp.alerts import Alerts
from pti_exp.compose import Compose
from pti_exp.metrics import Scrape, delta_buckets, quantile
from pti_exp.runner import InvalidRun, Run, Sampler, drain, groups, iso, now, quiesce
from pti_exp.sim import Simulator

EXPECTED_ALERTS = ["ConsumerLagHigh", "EndToEndLatencyHigh", "LatencyStageSlow", "ThroughputDrop",
                   "DatabaseBottleneck", "SimulatorLagging"]
GTFS = "~GTFS_RT_.*"
LIMITS = {"etl-stream": 2.0, "pg-warehouse": 2.0, "kafka": 1.0, "source-simulator": 1.0}
GROUP = "pti-etl-gtfs-rt"


class Stats:
    """docker stats every ~5 s for the containers that bound the load (DOC-45 EXP-05 §5 step 5)."""

    def __init__(self, services: list[str]) -> None:
        self.services, self.rows = services, []
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._loop, daemon=True)

    def start(self) -> Stats:
        self._thread.start()
        return self

    def stop(self) -> None:
        self._stop.set()
        self._thread.join(timeout=30)

    def _loop(self) -> None:
        compose = Compose()
        while not self._stop.is_set():
            for service in self.services:
                try:
                    s = compose.container(service).stats(stream=False)
                    cpu = s["cpu_stats"]["cpu_usage"]["total_usage"] - s["precpu_stats"]["cpu_usage"]["total_usage"]
                    system = s["cpu_stats"].get("system_cpu_usage", 0) - s["precpu_stats"].get("system_cpu_usage", 0)
                    cores = s["cpu_stats"].get("online_cpus", 1)
                    self.rows.append({"t": now(), "service": service,
                                      "cpu": cpu / system * cores if system > 0 else 0.0,
                                      "mem": s["memory_stats"].get("usage", 0)})
                except Exception:
                    continue
            self._stop.wait(5)


def run(r: Run, series: str) -> dict:
    p = r.params
    compose, sim = Compose(), Simulator(r.stack.sim_url, r.requested_by)
    alerts = Alerts(r.stack.alertmanager_url, r.stack.prometheus_url)
    r.write_config(list(LIMITS))
    # No baseline during a load test (EXP-05 §3): it would double the database load.
    baseline_was_running = compose.running("etl-stream-baseline")
    if baseline_was_running:
        compose.stop("etl-stream-baseline")
    silences = alerts.silence(EXPECTED_ALERTS, timedelta(minutes=40), f"{r.exp}/{r.run_id}")
    sampler = Sampler(r.stack, [GROUP], ["etl-stream"]).start()
    stats = Stats(list(LIMITS)).start()
    steps: list[dict] = []
    try:
        sim.rate(gtfs_rt=1.0, ticketing=1.0)
        time.sleep(p["settle"].total_seconds())
        lag_start = sampler.last_lag(GROUP) or 0
        t0 = now()
        started = sim.start("load-ramp", {"steps": p["steps"], "stepDuration": f"PT{int(p['step'].total_seconds())}S",
                                          "rampDown": False, "includeTicketing": False})
        for i, multiplier in enumerate(p["steps"]):
            step_start = t0 + i * p["step"]
            segment_start = step_start + p["skip"]
            segment_end = t0 + (i + 1) * p["step"]
            _sleep_until(segment_start)
            first = (Scrape.of(r.stack.actuator("etl-stream")), Scrape.of(r.stack.actuator("source-simulator")))
            _sleep_until(segment_end - timedelta(seconds=2))
            last = (Scrape.of(r.stack.actuator("etl-stream")), Scrape.of(r.stack.actuator("source-simulator")))
            steps.append(_step(i, multiplier, segment_start, now(), first, last, sampler, stats, p["threshold"]))
        _sleep_until(t0 + len(p["steps"]) * p["step"] + timedelta(seconds=2))
        t1 = now()
        drain_seconds = _drain_to(sampler, lag_start, p["drain_timeout"])
        t_end = quiesce(sim)
        drain(groups(False))
    finally:
        sampler.stop()
        stats.stop()
        alerts.unsilence(silences)
        sim.stop("load-ramp")
        if baseline_was_running:
            compose.start("etl-stream-baseline")
    r.write_timeseries(sampler.rows)

    result = truth.correctness(r.stack, t0, t_end, baseline=False)
    passing = [s["multiplier"] for s in steps if s["valid"] and s["step_pass"]]
    threshold = None
    for s in steps:
        if not (s["valid"] and s["step_pass"]):
            break
        threshold = s["multiplier"]
    r.summary = {
        "window": {"t0": iso(t0), "t1": iso(t1), "end": iso(t_end)}, "series": series,
        "scenario_run": started.get("runId"), "steps": steps,
        "threshold_multiplier": threshold if threshold != p["steps"][-1] else f">= {threshold}",
        "threshold_msgs": next((s["throughput_in"] for s in steps if s["multiplier"] == threshold), None),
        "passing_steps": passing, "drain_seconds": drain_seconds,
        "normal": result["normal"],
        "alerts": {"fired": alerts.fired(t0, t1)},
    }
    return r.summary


def _step(i, multiplier, start: datetime, end: datetime, first, last, sampler: Sampler, stats: Stats,
          threshold: float) -> dict:
    seconds = (end - start).total_seconds()
    etl0, sim0 = first
    etl1, sim1 = last
    buckets = delta_buckets(etl0.buckets("pti_etl_kafka_to_commit_seconds", source=GTFS),
                            etl1.buckets("pti_etl_kafka_to_commit_seconds", source=GTFS))
    throughput_in = (sim1.sum("pti_sim_messages_sent_total") - sim0.sum("pti_sim_messages_sent_total")) / seconds
    throughput_out = (etl1.sum("pti_etl_records_total", mode="stream", source=GTFS)
                      - etl0.sum("pti_etl_records_total", mode="stream", source=GTFS)) / seconds
    lags = [(row["t"], row[f"lag:{GROUP}"]) for row in sampler.rows
            if f"lag:{GROUP}" in row and iso(start) <= row["t"] <= iso(end)]
    slope = _slope(lags[len(lags) // 2:])
    restarted = etl0.max("process_start_time_seconds") != etl1.max("process_start_time_seconds")
    circuit_open = (etl1.max("resilience4j_circuitbreaker_state", state="open") or 0) >= 1
    tick_lags = [row.get("tick_lag") for row in sampler.rows if iso(start) <= row["t"] <= iso(end)]
    p95 = quantile(0.95, buckets)
    usage = {}
    for service in LIMITS:
        values = [s for s in stats.rows if s["service"] == service and start <= s["t"] <= end]
        if values:
            cpu = [v["cpu"] for v in values]
            usage[service] = {"cpu_mean": statistics.fmean(cpu), "cpu_max": max(cpu),
                              "cpu_util": statistics.fmean(cpu) / LIMITS[service],
                              "mem_max": max(v["mem"] for v in values)}
    step_pass = (p95 is not None and p95 < threshold and slope is not None
                 and slope <= 0.02 * max(throughput_in, 1e-9) and not restarted and not circuit_open)
    return {"step": i + 1, "multiplier": multiplier, "seconds": seconds,
            "latency_p50": quantile(0.5, buckets), "latency_p95": p95, "latency_p99": quantile(0.99, buckets),
            "throughput_in": throughput_in, "throughput_out": throughput_out,
            "lag_end": lags[-1][1] if lags else None, "lag_slope": slope,
            "pool_wait": etl1.max("hikaricp_connections_pending"), "restarted": restarted,
            "circuit_open": circuit_open, "usage": usage, "step_pass": step_pass,
            "valid": all(v is None or v <= 2 for v in tick_lags)}


def _slope(points: list[tuple[str, int]]) -> float | None:
    """Least-squares slope of the lag, in messages per second."""
    if len(points) < 3:
        return None
    t0 = datetime.fromisoformat(points[0][0].replace("Z", "+00:00"))
    xs = [(datetime.fromisoformat(t.replace("Z", "+00:00")) - t0).total_seconds() for t, _ in points]
    ys = [float(v) for _, v in points]
    mean_x, mean_y = statistics.fmean(xs), statistics.fmean(ys)
    den = sum((x - mean_x) ** 2 for x in xs)
    return sum((x - mean_x) * (y - mean_y) for x, y in zip(xs, ys, strict=True)) / den if den else None


def _drain_to(sampler: Sampler, lag_start: int, timeout: timedelta) -> float:
    """C4: seconds until the lag is back to its level before the ramp (plus a small margin)."""
    started = time.monotonic()
    target = max(lag_start * 1.1, 500)
    while time.monotonic() - started < timeout.total_seconds():
        lag = sampler.last_lag(GROUP)
        if lag is not None and lag <= target:
            return time.monotonic() - started
        time.sleep(2)
    raise InvalidRun(f"lag did not return to {target} within {timeout}")


def _sleep_until(when: datetime) -> None:
    delay = (when - now()).total_seconds()
    if delay > 0:
        time.sleep(delay)


def criteria(summary: dict) -> list[str]:
    """C3 of EXP-05 §7; the threshold (C1, C2) is only reported by the smoke chain (DOC-45 §1.3)."""
    return truth.violations(summary["normal"])
