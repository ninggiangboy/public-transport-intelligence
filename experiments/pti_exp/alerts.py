"""Alertmanager silences around an expected fault, and the alerts that fired during a run (DOC-28 §6.5)."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

import httpx


class Alerts:
    def __init__(self, alertmanager_url: str, prometheus_url: str) -> None:
        self.alertmanager = alertmanager_url
        self.prometheus = prometheus_url

    def available(self) -> bool:
        try:
            return httpx.get(self.alertmanager + "/-/ready", timeout=3).status_code == 200
        except httpx.HTTPError:
            return False

    def silence(self, names: list[str], duration: timedelta, comment: str) -> list[str]:
        """One silence per alert name (matching on alertname only, DOC-45 EXP-01 step 2)."""
        if not names or not self.available():
            return []
        now = datetime.now(UTC)
        ids = []
        for name in names:
            body = {"matchers": [{"name": "alertname", "value": name, "isRegex": False, "isEqual": True}],
                    "startsAt": now.isoformat(), "endsAt": (now + duration).isoformat(),
                    "createdBy": "pti-exp", "comment": comment}
            response = httpx.post(self.alertmanager + "/api/v2/silences", json=body, timeout=10)
            response.raise_for_status()
            ids.append(response.json()["silenceID"])
        return ids

    def unsilence(self, ids: list[str]) -> None:
        for silence in ids:
            httpx.delete(f"{self.alertmanager}/api/v2/silence/{silence}", timeout=10)

    def fired(self, t0: datetime, t1: datetime) -> list[str]:
        """Names of alerts that were firing at some point in [t0, t1], from Prometheus' ALERTS series."""
        seconds = max(60, int((t1 - t0).total_seconds()))
        try:
            response = httpx.get(self.prometheus + "/api/v1/query", timeout=10, params={
                "query": f'max by (alertname) (max_over_time(ALERTS{{alertstate="firing"}}[{seconds}s]))',
                "time": t1.timestamp()})
            response.raise_for_status()
        except httpx.HTTPError:
            return []
        return sorted(r["metric"]["alertname"] for r in response.json()["data"]["result"])
