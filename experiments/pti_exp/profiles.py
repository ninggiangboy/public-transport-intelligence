"""Parameters of each experiment: `full` follows the protocol files (EXP-01…05 §2, §5), `smoke` the 30-minute chain
of DOC-45 §1.3 (DR-95). The runners are the same; only these numbers differ."""

from __future__ import annotations

from datetime import timedelta

M = timedelta(minutes=1)
S = timedelta(seconds=1)

PROFILES: dict[str, dict[str, dict]] = {
    "EXP-01": {
        "full": {"window": 600 * S, "fault_from": 60 * S, "fault_to": 540 * S, "rate": 2.0, "settle": 60 * S,
                 "restart_after": 5 * S, "outage": 30 * S, "window_needed": 25 * M},
        "smoke": {"window": 240 * S, "fault_from": 60 * S, "fault_to": 180 * S, "rate": 2.0, "settle": 30 * S,
                  "restart_after": 5 * S, "outage": 30 * S, "window_needed": 8 * M},
    },
    "EXP-02": {
        "full": {"ratio": 0.10, "duration": 10 * M, "min_delay": 0 * S, "max_delay": 60 * S, "settle": 60 * S,
                 "window_needed": 20 * M},
        "smoke": {"ratio": 0.10, "duration": 3 * M, "min_delay": 0 * S, "max_delay": 60 * S, "settle": 30 * S,
                  "window_needed": 6 * M},
    },
    "EXP-03": {
        "full": {"ratio": 0.05, "duration": 15 * M, "settle": 60 * S, "window_needed": 25 * M},
        "smoke": {"ratio": 0.05, "duration": 3 * M, "settle": 30 * S, "window_needed": 6 * M},
    },
    "EXP-04": {
        "full": {"load": 30 * M, "rate": 2.0, "bad_ratio": 0.01, "dup_ratio": 0.05, "settle_raw": 12 * M,
                 "rebuild_twice": True},
        "smoke": {"rebuild_twice": False},
    },
    "EXP-05": {
        "full": {"steps": [1, 2, 3, 5, 7, 10], "step": 5 * M, "skip": 60 * S, "settle": 3 * M,
                 "drain_timeout": 10 * M, "threshold": 3.0},
        "smoke": {"steps": [1, 3, 5, 10], "step": 90 * S, "skip": 30 * S, "settle": 30 * S,
                  "drain_timeout": 3 * M, "threshold": 3.0},
    },
}


def params(exp: str, profile: str) -> dict:
    return dict(PROFILES[exp][profile])


def jsonable(values: dict) -> dict:
    return {k: (v.total_seconds() if isinstance(v, timedelta) else v) for k, v in values.items()}
