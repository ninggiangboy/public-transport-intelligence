#!/usr/bin/env python3
"""Runs every query of every PTI dashboard through the Grafana API (DOC-28 §9 O-09).

Prints one line per query: OK (returns data), EMPTY (valid, no data now) or ERROR (the query failed). Exits 1 on any
ERROR, and with --strict on any EMPTY too. Template variables become ".*", $__range becomes 1h.

Usage: check-dashboards.py [--strict]   (GRAFANA_URL, GRAFANA_USER, GRAFANA_PASSWORD from the environment)
"""
import base64
import json
import os
import re
import sys
import urllib.error
import urllib.request

URL = os.environ.get("GRAFANA_URL", "http://localhost:3000")
AUTH = base64.b64encode(
    f"{os.environ.get('GRAFANA_USER', 'admin')}:{os.environ['GRAFANA_PASSWORD']}".encode()).decode()


def call(path, body=None):
    request = urllib.request.Request(URL + path, data=None if body is None else json.dumps(body).encode(),
                                     headers={"Authorization": "Basic " + AUTH, "Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def resolve(expr):
    expr = expr.replace("$__range", "1h").replace("$__rate_interval", "5m")
    return re.sub(r"\$\{?\w+\}?", ".*", expr)


def run(datasource, target):
    query = dict(target, refId="A", datasource=datasource, intervalMs=15000, maxDataPoints=100)
    if "expr" in query:
        query["expr"] = resolve(query["expr"])
        query["instant"] = True
        query["range"] = False
    result = call("/api/ds/query", {"from": "now-1h", "to": "now", "queries": [query]})["results"]["A"]
    if result.get("error"):
        return "ERROR", result["error"]
    frames = result.get("frames", [])
    rows = sum(len(f.get("data", {}).get("values", [[]])[0] or []) for f in frames if f.get("data", {}).get("values"))
    return ("OK" if rows else "EMPTY"), f"{rows} rows"


def main():
    strict = "--strict" in sys.argv
    errors = empties = 0
    for hit in call("/api/search?tag=pti&type=dash-db"):
        board = call(f"/api/dashboards/uid/{hit['uid']}")["dashboard"]
        for panel in board.get("panels", []):
            for target in panel.get("targets", []):
                datasource = target.get("datasource") or panel.get("datasource")
                try:
                    status, detail = run(datasource, target)
                except urllib.error.HTTPError as e:
                    status, detail = "ERROR", e.read().decode()[:200]
                errors += status == "ERROR"
                empties += status == "EMPTY"
                print(f"{status:5} {board['uid']:16} {panel['title'][:60]:60} {detail}")
    print(f"{errors} errors, {empties} empty")
    return 1 if errors or (strict and empties) else 0


if __name__ == "__main__":
    sys.exit(main())
