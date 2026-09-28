"""Cuts the mini GTFS test feed out of the real Metro Transit feed (DOC-44 §5.1).

Deterministic: the same source zip always produces byte-identical output. Rows are copied verbatim
(original quoting and line endings), in the order they appear in the source file.

Selection:
  - routes 18 and 901;
  - their trips whose service runs on 2026-09-29 (Tuesday) or 2026-10-03 (Saturday), and whose first
    departure is in [16:00:00, 17:00:00), plus, per service_id, the route 18 trip that ends latest
    after midnight (arrival >= 24:00:00);
  - the stop_times and shapes of those trips, the stops they reference and their parent stations;
  - the agencies of the two routes, feed_info as is, the calendar rows of the kept services, and their
    calendar_dates between the two days;
  - the first 60 vehicles by vehicle_id (string order).

Usage: python3 sample-data/gtfs/make_mini_feed.py [source.zip] [output_dir]
"""
import csv
import datetime as dt
import hashlib
import io
import os
import sys
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DEFAULT_SOURCE = os.path.join(REPO, "sample-data/gtfs/metrotransit-mn-20260926.zip")
DEFAULT_OUTPUT = os.path.join(REPO, "backend/common/src/testFixtures/resources/gtfs/mini")

ROUTES = ("18", "901")
DAYS = (dt.date(2026, 9, 29), dt.date(2026, 10, 3))
WINDOW = ("16:00:00", "17:00:00")
LATE_ROUTE = "18"
VEHICLES = 60


def read(zf, name):
    """Returns (header line, header fields, [(raw line, fields)])."""
    text = zf.read(name).decode("utf-8")
    lines = text.splitlines(keepends=True)
    header = lines[0]
    fields = next(csv.reader([header.lstrip("﻿").rstrip("\r\n")]))
    rows = []
    for line in lines[1:]:
        stripped = line.rstrip("\r\n")
        if stripped:
            rows.append((line, dict(zip(fields, next(csv.reader([stripped]))))))
    return header, rows


def write(out, name, header, rows):
    with open(os.path.join(out, name), "w", encoding="utf-8", newline="") as f:
        f.write(header)
        for line, _ in rows:
            f.write(line)


def gtfs_time(value):
    h, m, s = (int(x) for x in value.split(":"))
    return h * 3600 + m * 60 + s


def main(source, out):
    zf = zipfile.ZipFile(source)
    files = {name: read(zf, name) for name in (
        "agency.txt", "feed_info.txt", "routes.txt", "trips.txt", "stop_times.txt", "stops.txt",
        "calendar.txt", "calendar_dates.txt", "shapes.txt", "vehicles.txt")}

    calendar = {r["service_id"]: r for _, r in files["calendar.txt"][1]}
    exceptions = {(r["service_id"], r["date"]): r["exception_type"] for _, r in files["calendar_dates.txt"][1]}

    def runs_on(service_id, day):
        key = day.strftime("%Y%m%d")
        exception = exceptions.get((service_id, key))
        if exception is not None:
            return exception == "1"
        c = calendar.get(service_id)
        return c is not None and c["start_date"] <= key <= c["end_date"] and c[day.strftime("%A").lower()] == "1"

    candidates = {r["trip_id"]: r for _, r in files["trips.txt"][1]
                  if r["route_id"] in ROUTES and any(runs_on(r["service_id"], d) for d in DAYS)}
    first, last = {}, {}
    for _, r in files["stop_times.txt"][1]:
        trip = r["trip_id"]
        if trip in candidates:
            dep, arr = gtfs_time(r["departure_time"]), gtfs_time(r["arrival_time"])
            first[trip] = min(first.get(trip, dep), dep)
            last[trip] = max(last.get(trip, arr), arr)

    lo, hi = gtfs_time(WINDOW[0]), gtfs_time(WINDOW[1])
    kept = {t for t in candidates if lo <= first[t] < hi}
    latest = {}
    for trip, r in candidates.items():
        if r["route_id"] == LATE_ROUTE and last[trip] >= 24 * 3600:
            current = latest.get(r["service_id"])
            if current is None or (last[trip], trip) > (last[current], current):
                latest[r["service_id"]] = trip
    kept |= set(latest.values())

    trips = [(l, r) for l, r in files["trips.txt"][1] if r["trip_id"] in kept]
    services = {r["service_id"] for _, r in trips}
    shapes = {r["shape_id"] for _, r in trips}
    stop_times = [(l, r) for l, r in files["stop_times.txt"][1] if r["trip_id"] in kept]

    all_stops = {r["stop_id"]: (l, r) for l, r in files["stops.txt"][1]}
    stop_ids = {r["stop_id"] for _, r in stop_times}
    stop_ids |= {all_stops[s][1]["parent_station"] for s in list(stop_ids) if all_stops[s][1].get("parent_station")}

    routes = [(l, r) for l, r in files["routes.txt"][1] if r["route_id"] in ROUTES]
    agencies = {r["agency_id"] for _, r in routes}
    lo_day, hi_day = (d.strftime("%Y%m%d") for d in DAYS)

    vehicle_ids = sorted(r["vehicle_id"] for _, r in files["vehicles.txt"][1])[:VEHICLES]

    selected = {
        "agency.txt": [(l, r) for l, r in files["agency.txt"][1] if r["agency_id"] in agencies],
        "feed_info.txt": files["feed_info.txt"][1],
        "routes.txt": routes,
        "trips.txt": trips,
        "stop_times.txt": stop_times,
        "stops.txt": [(l, r) for l, r in files["stops.txt"][1] if r["stop_id"] in stop_ids],
        "calendar.txt": [(l, r) for l, r in files["calendar.txt"][1] if r["service_id"] in services],
        "calendar_dates.txt": [(l, r) for l, r in files["calendar_dates.txt"][1]
                               if r["service_id"] in services and lo_day <= r["date"] <= hi_day],
        "shapes.txt": [(l, r) for l, r in files["shapes.txt"][1] if r["shape_id"] in shapes],
        "vehicles.txt": [(l, r) for l, r in files["vehicles.txt"][1] if r["vehicle_id"] in set(vehicle_ids)],
    }

    os.makedirs(out, exist_ok=True)
    for name, rows in selected.items():
        write(out, name, files[name][0], rows)

    sha = hashlib.sha256(open(source, "rb").read()).hexdigest()
    with open(os.path.join(out, "SOURCE.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write(f"Generated by sample-data/gtfs/make_mini_feed.py. Do not edit by hand.\n")
        f.write(f"source: {os.path.basename(source)}\n")
        f.write(f"source_sha256: {sha}\n")
        for name, rows in selected.items():
            f.write(f"{name}: {len(rows)} rows\n")
    print(f"{len(trips)} trips, {len(stop_times)} stop_times -> {out}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else DEFAULT_SOURCE,
         sys.argv[2] if len(sys.argv) > 2 else DEFAULT_OUTPUT)
