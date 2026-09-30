"""Checksums and per-key fingerprints of warehouse tables (DR-58, DOC-45 §4.5), from the files in `sql/`."""

from __future__ import annotations

import re
from datetime import datetime

from pti_exp.config import SQL, Stack
from pti_exp.db import connect


def named(query: str) -> str:
    """`:name` parameters (shared with WarehouseAssert, DOC-44 §5.3) to psycopg's `%(name)s`."""
    return re.sub(r"(?<!:):([a-z_]+)\b", r"%(\1)s", query.replace("%", "%%"))


def checksum(stack: Stack, table: str, start: datetime, end: datetime) -> dict:
    query = named((SQL / "checksum" / f"{table}.sql").read_text())
    with connect(stack, "pti_warehouse") as c:
        c.execute("SET TIME ZONE 'UTC'")
        count, digest = c.execute(query, {"from": start, "to": end}).fetchone()
    return {"row_count": count, "checksum": digest}


def fingerprints(stack: Stack, table: str, keys: list[str]) -> dict[str, str]:
    """business key → md5 of the checksum columns, for the given keys only. The keys of `fact_ticket_sales` are
    transaction ids: the source database, which is the truth for ticketing (DR-28), knows no sale date."""
    if not keys:
        return {}
    query = (SQL / "rows" / f"{table}.sql").read_text().strip().rstrip(";")
    query = "\n".join(line for line in query.splitlines() if not line.startswith("--"))
    where, params = "bkey = ANY(%(keys)s::text[])", {"keys": keys}
    if table == "fact_vehicle_position":
        times = [k.split("|", 1)[1] for k in keys]
        where += " AND event_timestamp BETWEEN %(low)s::timestamptz AND %(high)s::timestamptz"
        params |= {"low": min(times), "high": max(times)}
    elif table == "fact_trip_update":
        dates = [k.split("|", 1)[0] for k in keys]
        where += " AND service_date BETWEEN %(low)s::date AND %(high)s::date"
        params |= {"low": min(dates), "high": max(dates)}
    elif table == "fact_ticket_sales":
        where = "transaction_id = ANY(%(ids)s::uuid[])"
        params = {"ids": keys}
    with connect(stack, "pti_warehouse") as c:
        c.execute("SET TIME ZONE 'UTC'")
        return dict(c.execute(f"SELECT bkey, fp FROM ({query}) r WHERE {where}", params).fetchall())
