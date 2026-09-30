"""Statistics of DOC-45 §6 that need no extra library."""

from __future__ import annotations

import random
import statistics


def rule_of_three(runs: int) -> float | None:
    """95% upper bound on the probability that one run violates, when none of `runs` did."""
    return 3 / runs if runs else None


def percentile(values: list[float], q: float) -> float:
    data = sorted(values)
    if not data:
        raise ValueError("no values")
    k = (len(data) - 1) * q
    low = int(k)
    high = min(low + 1, len(data) - 1)
    return data[low] + (data[high] - data[low]) * (k - low)


def bootstrap_ci(values: list[float], stat, resamples: int = 10_000, seed: int = 1) -> tuple[float, float]:
    """95% bootstrap interval of `stat` over runs (DOC-45 §6)."""
    rng = random.Random(seed)
    estimates = sorted(stat([rng.choice(values) for _ in values]) for _ in range(resamples))
    return estimates[int(0.025 * resamples)], estimates[int(0.975 * resamples) - 1]


def describe(values: list[float]) -> dict[str, float]:
    if not values:
        return {}
    return {"n": len(values), "mean": statistics.fmean(values), "median": statistics.median(values),
            "p95": percentile(values, 0.95), "max": max(values)}
