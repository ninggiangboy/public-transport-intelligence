"""Scrapes /actuator/prometheus directly (DOC-45 §4.4): counters, gauges and histogram quantiles from bucket deltas,
so that nothing depends on Prometheus retention or its scrape interval."""

from __future__ import annotations

import math
import re
from collections.abc import Iterable
from dataclasses import dataclass

import httpx

SAMPLE = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{(.*)\})?\s+(\S+)')
LABEL = re.compile(r'([a-zA-Z_][a-zA-Z0-9_]*)="((?:[^"\\]|\\.)*)"')


@dataclass(frozen=True)
class Sample:
    name: str
    labels: dict[str, str]
    value: float


def parse(text: str) -> list[Sample]:
    samples = []
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        m = SAMPLE.match(line)
        if not m:
            continue
        labels = dict(LABEL.findall(m.group(2) or ""))
        try:
            value = float(m.group(3))
        except ValueError:
            continue
        samples.append(Sample(m.group(1), labels, value))
    return samples


class Scrape:
    """One scrape of an app."""

    def __init__(self, samples: Iterable[Sample]) -> None:
        self.samples = list(samples)

    @classmethod
    def of(cls, url: str) -> Scrape:
        response = httpx.get(url + "/actuator/prometheus", timeout=10)
        response.raise_for_status()
        return cls(parse(response.text))

    @classmethod
    def empty(cls) -> Scrape:
        return cls([])

    def sum(self, name: str, **match: str) -> float:
        return sum(s.value for s in self.samples if s.name == name and _matches(s.labels, match))

    def max(self, name: str, **match: str) -> float | None:
        values = [s.value for s in self.samples if s.name == name and _matches(s.labels, match)]
        return max(values) if values else None

    def buckets(self, name: str, **match: str) -> dict[float, float]:
        """Cumulative bucket counts of a histogram, summed over the matching series, by upper bound."""
        result: dict[float, float] = {}
        for s in self.samples:
            if s.name == name + "_bucket" and _matches(s.labels, match):
                bound = math.inf if s.labels["le"] == "+Inf" else float(s.labels["le"])
                result[bound] = result.get(bound, 0.0) + s.value
        return result


def _matches(labels: dict[str, str], match: dict[str, str]) -> bool:
    for key, want in match.items():
        value = labels.get(key)
        if want.startswith("~"):
            if value is None or not re.fullmatch(want[1:], value):
                return False
        elif value != want:
            return False
    return True


def delta_buckets(before: dict[float, float], after: dict[float, float]) -> dict[float, float]:
    """Bucket counts added between two scrapes; a counter reset (process restart) counts from zero."""
    result = {}
    for bound, value in after.items():
        previous = before.get(bound, 0.0)
        result[bound] = value - previous if value >= previous else value
    return result


def quantile(q: float, buckets: dict[float, float]) -> float | None:
    """histogram_quantile over cumulative buckets, with linear interpolation inside the bucket."""
    if not buckets:
        return None
    bounds = sorted(buckets)
    total = buckets[bounds[-1]]
    if total <= 0:
        return None
    rank = q * total
    previous_bound, previous_count = 0.0, 0.0
    for bound in bounds:
        count = buckets[bound]
        if count >= rank:
            if math.isinf(bound):
                return previous_bound
            if count == previous_count:
                return bound
            return previous_bound + (bound - previous_bound) * (rank - previous_count) / (count - previous_count)
        previous_bound, previous_count = bound, count
    return bounds[-2] if len(bounds) > 1 else None
