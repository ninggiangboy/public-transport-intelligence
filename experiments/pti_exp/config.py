"""Where the compose stack is and how to reach it (DOC-45 §2, DOC-38 §5).

Values come from the repository's .env, written by `make secrets`, with the documented defaults for host ports.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import timedelta
from pathlib import Path
from typing import ClassVar

REPO = Path(__file__).resolve().parents[2]
RESULTS = REPO / "experiments" / "results"
SQL = REPO / "experiments" / "sql"

GTFS_RT_TOPICS = ("gtfs.vehicle_positions", "gtfs.trip_updates")
STREAM_GROUPS = ("pti-etl-gtfs-rt", "pti-etl-ticketing")
BASELINE_GROUP = "pti-exp-baseline"
# What `make up-exp` sets: every recreated container of an experiment stack keeps it (DOC-38 §4).
EXP_ENV = {"PTI_WAREHOUSE_HOST": "toxiproxy", "PTI_TRACING_ENABLED": "true", "PTI_SIM_START_RATE": "1"}


def read_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.exists():
        return values
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def parse_offset(text: str) -> timedelta:
    """PTI_CLOCK_OFFSET as written by clock-offset.sh or this runner: '-672m', '19h40m', '0s', '' (DR-67)."""
    text = (text or "").strip()
    if not text or text in ("0", "0s"):
        return timedelta(0)
    match = re.fullmatch(r"(-)?(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?", text)
    if not match or not any(match.group(i) for i in (2, 3, 4)):
        raise ValueError(f"Unsupported PTI_CLOCK_OFFSET {text!r}")
    delta = timedelta(hours=int(match.group(2) or 0), minutes=int(match.group(3) or 0),
                      seconds=int(match.group(4) or 0))
    return -delta if match.group(1) else delta


def format_offset(delta: timedelta) -> str:
    minutes = int(delta.total_seconds() // 60)
    return f"{minutes}m"


@dataclass
class Stack:
    """The compose stack of this repository."""

    env: dict[str, str] = field(default_factory=lambda: read_env(REPO / ".env"))

    def port(self, name: str, default: int) -> int:
        value = self.env.get(f"HOST_PORT_{name}", "")
        return int(value) if value else default

    def password(self, variable: str) -> str:
        value = self.env.get(variable, "")
        if not value:
            raise RuntimeError(f"{variable} is not set in .env; run make secrets")
        return value

    @property
    def sim_url(self) -> str:
        return f"http://127.0.0.1:{self.port('SIM', 8084)}"

    @property
    def prometheus_url(self) -> str:
        return f"http://127.0.0.1:{self.port('PROMETHEUS', 9090)}"

    @property
    def alertmanager_url(self) -> str:
        return f"http://127.0.0.1:{self.port('ALERTMANAGER', 9093)}"

    @property
    def grafana_url(self) -> str:
        return f"http://127.0.0.1:{self.port('GRAFANA', 3000)}"

    @property
    def toxiproxy_url(self) -> str:
        return f"http://127.0.0.1:{self.port('TOXIPROXY', 8474)}"

    # Actuator ports of the apps (DOC-38 §5).
    ACTUATOR: ClassVar[dict[str, int]] = {
        "etl-stream": 9082, "etl-batch": 9083, "source-simulator": 9084, "etl-stream-baseline": 9086}

    def actuator(self, app: str) -> str:
        return f"http://127.0.0.1:{self.ACTUATOR[app]}"

    def dsn(self, database: str) -> str:
        port = self.port("PG_WAREHOUSE", 15432) if database == "pti_warehouse" else self.port("PG_SOURCE", 15433)
        password = self.password("EXPERIMENT_RUNNER_PASSWORD")
        return f"host=127.0.0.1 port={port} dbname={database} user=experiment_runner password={password}"

    @property
    def clock_offset(self) -> timedelta:
        return parse_offset(self.env.get("PTI_CLOCK_OFFSET", ""))

    def write_offset(self, offset: timedelta) -> None:
        """Writes PTI_CLOCK_OFFSET to .env, as clock-offset.sh does; containers read it when recreated."""
        path = REPO / ".env"
        lines = path.read_text().splitlines()
        line = f"PTI_CLOCK_OFFSET={format_offset(offset)}"
        lines = [line if existing.startswith("PTI_CLOCK_OFFSET=") else existing for existing in lines]
        if line not in lines:
            lines.append(line)
        path.write_text("\n".join(lines) + "\n")
        self.env = read_env(path)

    def compose_command(self) -> list[str]:
        return ["docker", "compose", "-f", str(REPO / "deploy/compose/compose.yaml"), "--env-file",
                str(REPO / "deploy/versions.env"), "--env-file", str(REPO / ".env")]
