"""The life cycle of one run (DOC-45 §2.1): config.json, a 2-second sampler, the drain, summary.json and the heavy
files. Experiments build on `Run`; a run that cannot be measured raises `InvalidRun` and is recorded as such."""

from __future__ import annotations

import csv
import gzip
import json
import platform
import subprocess
import threading
import time
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

from pti_exp import kafka
from pti_exp.compose import Compose
from pti_exp.config import BASELINE_GROUP, REPO, RESULTS, STREAM_GROUPS, Stack, format_offset
from pti_exp.metrics import Scrape
from pti_exp.profiles import jsonable
from pti_exp.sim import Simulator


class InvalidRun(Exception):
    """The run could not be measured (DOC-45 §6); it is kept, marked invalid, and replaced by a new one."""


def now() -> datetime:
    return datetime.now(UTC)


def iso(dt: datetime | None) -> str | None:
    return dt.astimezone(UTC).isoformat().replace("+00:00", "Z") if dt else None


def git_state() -> tuple[str, bool]:
    sha = subprocess.run(["git", "-C", str(REPO), "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
    status = subprocess.run(["git", "-C", str(REPO), "status", "--porcelain", "--", ".",
                             ":(exclude)experiments/results", ":(exclude)experiments/archive"],
                            capture_output=True, text=True).stdout
    return sha, bool(status.strip())


@dataclass
class Sampler:
    """Every ~2 s: committed lag per group, simulator tick lag and active vehicles, container states (DOC-45 §2.1)."""

    stack: Stack
    groups: list[str]
    services: list[str]
    rows: list[dict[str, Any]] = field(default_factory=list)
    _stop: threading.Event = field(default_factory=threading.Event)
    _thread: threading.Thread | None = None

    def start(self) -> Sampler:
        self._thread = threading.Thread(target=self._loop, name="sampler", daemon=True)
        self._thread.start()
        return self

    def stop(self) -> None:
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=30)

    def _loop(self) -> None:
        compose = Compose()
        sim = Simulator(self.stack.sim_url)
        while not self._stop.is_set():
            started = time.monotonic()
            row: dict[str, Any] = {"t": iso(now())}
            try:
                for group, lag in kafka.lags(self.groups).items():
                    row[f"lag:{group}"] = lag
            except Exception as e:
                row["error"] = repr(e)[:200]
            try:
                tick = Scrape.of(self.stack.actuator("source-simulator")).max("pti_sim_tick_lag_seconds")
                row["tick_lag"] = tick
            except Exception:
                pass
            try:
                status = sim.status()
                row["active_vehicles"] = status.get("activeVehicles")
                row["gtfs_rt_rate"] = status["rate"]["gtfsRt"]
            except Exception:
                pass
            for service in self.services:
                row[f"state:{service}"] = "running" if compose.running(service) else "down"
            self.rows.append(row)
            self._stop.wait(max(0.0, 2.0 - (time.monotonic() - started)))

    def last_lag(self, group: str) -> int | None:
        for row in reversed(self.rows):
            if f"lag:{group}" in row:
                return row[f"lag:{group}"]
        return None


@dataclass
class Run:
    exp: str
    run_id: str
    directory: Path
    stack: Stack
    params: dict[str, Any]
    seed: int
    series: str
    profile: str
    summary: dict[str, Any] = field(default_factory=dict)

    @classmethod
    def create(cls, exp: str, series: str, index: int, profile: str, params: dict[str, Any], seed: int,
               stack: Stack, root: Path | None = None) -> Run:
        run_id = f"{series}-r{index:02d}"
        base = root or (RESULTS / "smoke" / series if profile == "smoke" else RESULTS)
        directory = base / exp / run_id
        directory.mkdir(parents=True, exist_ok=True)
        return cls(exp, run_id, directory, stack, params, seed, series, profile)

    @property
    def done(self) -> bool:
        return (self.directory / "summary.json").exists()

    @property
    def requested_by(self) -> str:
        return f"experiment:{self.exp}/{self.run_id}"

    def write_config(self, services: list[str]) -> None:
        sha, dirty = git_state()
        compose = Compose()
        digests = {}
        for service in services:
            try:
                digests[service] = compose.image_digest(service)
            except Exception:
                continue
        info = compose.client.info()
        config = {
            "exp": self.exp, "run_id": self.run_id, "series": self.series, "profile": self.profile,
            "git_sha": sha, "dirty": dirty, "image_digests": digests, "params": jsonable(self.params),
            "seed": self.seed,
            "clock_offset": format_offset(self.stack.clock_offset),
            "machine": {"platform": platform.platform(), "docker_cpus": info.get("NCPU"),
                        "docker_memory_bytes": info.get("MemTotal"), "docker_version": info.get("ServerVersion"),
                        "architecture": info.get("Architecture")},
            "created_at": iso(now()),
        }
        (self.directory / "config.json").write_text(json.dumps(config, indent=2) + "\n")

    def write_summary(self, valid: bool = True, reason: str | None = None) -> dict[str, Any]:
        self.summary = {"exp": self.exp, "run_id": self.run_id, "profile": self.profile, "valid": valid,
                        "invalid_reason": reason, **self.summary}
        (self.directory / "summary.json").write_text(json.dumps(self.summary, indent=2, default=str) + "\n")
        return self.summary

    def write_timeseries(self, rows: list[dict[str, Any]]) -> None:
        if not rows:
            return
        columns = sorted({k for r in rows for k in r}, key=lambda k: (k != "t", k))
        with gzip.open(self.directory / "timeseries.csv.gz", "wt", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=columns)
            writer.writeheader()
            writer.writerows(rows)

    def write_keys_diff(self, rows: list[tuple[str, str, str]]) -> None:
        with gzip.open(self.directory / "keys_diff.csv.gz", "wt", newline="") as f:
            writer = csv.writer(f)
            writer.writerow(["table", "problem", "business_key"])
            writer.writerows(rows)


def quiesce(sim: Simulator) -> datetime:
    """Stops the simulator and returns the end of the window once the tick in flight has been sent: a message produced
    after the window would overwrite a trip-update row that the window expects (DOC-45 §2.1)."""
    sim.rate(gtfs_rt=0, ticketing=0)
    time.sleep(3)
    return now()


def drain(groups: list[str], hold: timedelta = timedelta(seconds=30), timeout: timedelta = timedelta(minutes=10),
          sampler: Sampler | None = None) -> float:
    """Waits until the committed lag of every group is 0 for `hold`; returns the seconds it took (README §2.1)."""
    started = time.monotonic()
    zero_since: float | None = None
    while time.monotonic() - started < timeout.total_seconds():
        lags = kafka.lags(groups)
        if all(v == 0 for v in lags.values()):
            zero_since = zero_since or time.monotonic()
            if time.monotonic() - zero_since >= hold.total_seconds():
                return time.monotonic() - started
        else:
            zero_since = None
        time.sleep(2)
    raise InvalidRun(f"drain did not finish within {timeout}: {kafka.lags(groups)}")


def groups(baseline: bool) -> list[str]:
    return [*STREAM_GROUPS, BASELINE_GROUP] if baseline else list(STREAM_GROUPS)
