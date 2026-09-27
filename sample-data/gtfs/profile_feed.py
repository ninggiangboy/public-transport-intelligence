"""Quick GTFS feed profiler for spike S-02."""
import csv, io, sys, zipfile, datetime as dt, hashlib, collections

def rows(z, name):
    if name not in z.namelist():
        return
    with z.open(name) as f:
        yield from csv.DictReader(io.TextIOWrapper(f, encoding="utf-8-sig"))

def secs(t):
    h, m, s = (int(x) for x in t.strip().split(":"))
    return h * 3600 + m * 60 + s

def profile(path):
    z = zipfile.ZipFile(path)
    names = set(z.namelist())
    out = {"file": path, "size_mb": round(sum(i.file_size for i in z.infolist()) / 1e6, 1),
           "zip_mb": None, "sha256": hashlib.sha256(open(path, "rb").read()).hexdigest()[:16]}
    import os; out["zip_mb"] = round(os.path.getsize(path) / 1e6, 1)
    out["files"] = sorted(n for n in names if n.endswith(".txt"))
    agency = list(rows(z, "agency.txt"))
    out["agency"] = [a.get("agency_name") for a in agency][:5]
    out["tz"] = agency[0].get("agency_timezone") if agency else None
    fi = list(rows(z, "feed_info.txt"))
    if fi:
        out["feed_info"] = {k: fi[0].get(k) for k in ("feed_publisher_name", "feed_start_date", "feed_end_date", "feed_version", "feed_lang")}
    routes = list(rows(z, "routes.txt"))
    out["routes"] = len(routes)
    out["route_types"] = dict(collections.Counter(r["route_type"] for r in routes))
    rtype = {r["route_id"]: r["route_type"] for r in routes}
    out["stops"] = sum(1 for s in rows(z, "stops.txt") if s.get("location_type", "0") in ("", "0"))
    trips = list(rows(z, "trips.txt"))
    out["trips"] = len(trips)
    out["has_direction_id"] = sum(1 for t in trips if t.get("direction_id", "") != "") / max(len(trips), 1)
    out["has_shape_id"] = sum(1 for t in trips if t.get("shape_id", "") != "") / max(len(trips), 1)
    out["has_block_id"] = sum(1 for t in trips if t.get("block_id", "") != "") / max(len(trips), 1)
    # service calendar
    cal = list(rows(z, "calendar.txt"))
    cd = list(rows(z, "calendar_dates.txt"))
    dates = [c["start_date"] for c in cal] + [c["end_date"] for c in cal] + [c["date"] for c in cd]
    out["service_range"] = (min(dates), max(dates)) if dates else None
    # pick a weekday (Tuesday) inside the range, prefer the first full week
    start = dt.datetime.strptime(out["service_range"][0], "%Y%m%d").date()
    d = start + dt.timedelta(days=7)
    while d.weekday() != 1:
        d += dt.timedelta(days=1)
    ds = d.strftime("%Y%m%d")
    wd = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"][d.weekday()]
    active = {c["service_id"] for c in cal if c["start_date"] <= ds <= c["end_date"] and c[wd] == "1"}
    for c in cd:
        if c["date"] == ds:
            (active.add if c["exception_type"] == "1" else active.discard)(c["service_id"])
    out["sample_date"] = ds
    day_trips = {t["trip_id"]: t for t in trips if t["service_id"] in active}
    out["trips_on_sample_date"] = len(day_trips)
    # stop_times: first/last time per trip + shape_dist coverage
    first, last = {}, {}
    st_rows = 0; sdt = 0
    for r in rows(z, "stop_times.txt"):
        st_rows += 1
        if r.get("shape_dist_traveled", "") != "":
            sdt += 1
        tid = r["trip_id"]
        if tid not in day_trips:
            continue
        t = r.get("arrival_time") or r.get("departure_time")
        if not t:
            continue
        s = secs(t)
        if tid not in first or s < first[tid]:
            first[tid] = s
        if tid not in last or s > last[tid]:
            last[tid] = s
    out["stop_times_rows"] = st_rows
    out["stop_times_shape_dist_ratio"] = round(sdt / max(st_rows, 1), 3)
    out["has_frequencies"] = "frequencies.txt" in names
    # concurrency sweep per minute; vehicles ~ distinct blocks if block_id present
    events = collections.Counter()
    for tid in first:
        events[first[tid] // 60] += 1
        events[last[tid] // 60 + 1] -= 1
    cur = 0; peak = 0; peak_min = 0; series = {}
    for m in range(0, 30 * 60):
        cur += events.get(m, 0)
        series[m] = cur
        if cur > peak:
            peak, peak_min = cur, m
    out["peak_concurrent_trips"] = peak
    out["peak_time"] = f"{peak_min // 60:02d}:{peak_min % 60:02d}"
    out["concurrent_at_08_12_17"] = {h: series[h * 60] for h in (8, 12, 17)}
    bus_peak_types = collections.Counter(rtype.get(day_trips[t]["route_id"]) for t in first
                                         if first[t] <= peak_min * 60 <= last[t])
    out["peak_by_route_type"] = dict(bus_peak_types)
    return out

if __name__ == "__main__":
    import json
    for p in sys.argv[1:]:
        try:
            print(json.dumps(profile(p), indent=1, ensure_ascii=False))
        except Exception as e:
            print(p, "ERROR", repr(e))
