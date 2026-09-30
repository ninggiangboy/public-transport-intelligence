"""Read-only connections as experiment_runner (DOC-17 §4.2, DOC-45 §2)."""

from __future__ import annotations

from contextlib import contextmanager

import psycopg

from pti_exp.config import Stack


@contextmanager
def connect(stack: Stack, database: str):
    with psycopg.connect(stack.dsn(database), autocommit=True) as connection:
        yield connection


def truncate_baseline(stack: Stack) -> None:
    with connect(stack, "pti_warehouse") as c:
        c.execute("TRUNCATE exp.exp_fact_vehicle_position, exp.exp_fact_trip_update")
