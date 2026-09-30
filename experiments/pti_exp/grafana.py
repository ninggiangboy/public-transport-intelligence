"""Run annotations on the Grafana dashboards (tag experiment, DOC-28 §7)."""

from __future__ import annotations

import contextlib
from datetime import datetime

import httpx


def annotate(url: str, password: str, start: datetime, end: datetime, text: str, tags: list[str]) -> None:
    with contextlib.suppress(httpx.HTTPError):  # Grafana is optional (profile observability)
        httpx.post(url + "/api/annotations", auth=("admin", password), timeout=5, json={
            "time": int(start.timestamp() * 1000), "timeEnd": int(end.timestamp() * 1000),
            "tags": ["experiment", *tags], "text": text})
