"""The simulator control API (DOC-25 §8)."""

from __future__ import annotations

from typing import Any

import httpx


class Simulator:
    def __init__(self, base_url: str, requested_by: str = "cli") -> None:
        self.http = httpx.Client(base_url=base_url, timeout=30)
        self.requested_by = requested_by

    def status(self) -> dict[str, Any]:
        return self._ok(self.http.get("/sim/status"))

    def rate(self, gtfs_rt: float | None = None, ticketing: float | None = None) -> dict[str, Any]:
        body = {k: v for k, v in (("gtfsRt", gtfs_rt), ("ticketing", ticketing)) if v is not None}
        return self._ok(self.http.put("/sim/rate", json=body))

    def start(self, scenario: str, params: dict[str, Any]) -> dict[str, Any]:
        return self._ok(self.http.post(f"/sim/scenarios/{scenario}", json=params,
                                       headers={"X-Requested-By": self.requested_by}))

    def stop(self, scenario: str) -> None:
        response = self.http.delete(f"/sim/scenarios/{scenario}")
        if response.status_code not in (204, 404):
            response.raise_for_status()

    def run(self, run_id: str) -> dict[str, Any]:
        return self._ok(self.http.get(f"/sim/scenario-runs/{run_id}"))

    @staticmethod
    def _ok(response: httpx.Response) -> dict[str, Any]:
        if response.status_code >= 400:
            raise RuntimeError(f"{response.request.method} {response.request.url}: {response.status_code} "
                               f"{response.text[:500]}")
        return response.json()
