"""Committed lag of the consumer groups, read with kafka-consumer-groups.sh in the broker container (DOC-45 §4.2):
the clients' own lag metric disappears when the consumer dies."""

from __future__ import annotations

import subprocess

CONTAINER = "pti-kafka-1"


def describe(groups: list[str]) -> dict[str, dict[tuple[str, int], tuple[int | None, int]]]:
    """group → (topic, partition) → (committed offset or None, log-end offset)."""
    command = ["docker", "exec", "-e", "KAFKA_HEAP_OPTS=-Xmx128m", CONTAINER,
               "/opt/kafka/bin/kafka-consumer-groups.sh", "--bootstrap-server", "kafka:9092", "--describe"]
    for group in groups:
        command += ["--group", group]
    output = subprocess.run(command, capture_output=True, text=True, timeout=60).stdout
    return parse_describe(output)


def parse_describe(output: str) -> dict[str, dict[tuple[str, int], tuple[int | None, int]]]:
    result: dict[str, dict[tuple[str, int], tuple[int | None, int]]] = {}
    for line in output.splitlines():
        parts = line.split()
        if len(parts) < 5 or parts[0] == "GROUP" or not parts[2].isdigit():
            continue
        group, topic, partition, current, end = parts[:5]
        if not end.isdigit():
            continue
        committed = int(current) if current.isdigit() else None
        result.setdefault(group, {})[(topic, int(partition))] = (committed, int(end))
    return result


def committed_lag(state: dict[tuple[str, int], tuple[int | None, int]], topics: tuple[str, ...] | None = None) -> int:
    """Σ (log-end − committed); a partition with no committed offset counts its whole log-end."""
    total = 0
    for (topic, _), (committed, end) in state.items():
        if topics is None or topic in topics:
            total += end - (committed or 0)
    return total


def lags(groups: list[str]) -> dict[str, int]:
    state = describe(groups)
    return {group: committed_lag(state.get(group, {})) for group in groups}
