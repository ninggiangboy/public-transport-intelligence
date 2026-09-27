# GTFS static feed

Pinned snapshot of the GTFS schedule used by the source simulator and by `GtfsStaticLoadJob`.
It is pinned (not downloaded on demand) so that experiments are reproducible.

| Field | Value |
| --- | --- |
| File | `metrotransit-mn-20260926.zip` |
| Publisher | Metro Transit / Metropolitan Council (Minneapolis–St. Paul, MN, USA) |
| Source URL | https://svc.metrotransit.org/mtgtfs/gtfs.zip |
| Downloaded | 2026-09-26 |
| feed_version | `1790175878` |
| Feed validity | 2026-09-26 → 2026-12-04 (service calendar until 2026-11-13) |
| Timezone | `America/Chicago` (DST ends 2026-11-01) |
| License | Public data under the Minnesota Government Data Practices Act (Minn. Stat. ch. 13), see the [Minnesota Geospatial Commons listing](https://gisdata.mn.gov/dataset/us-mn-state-metc-trans-transit-schedule-google-fd). Attribution: "Schedule data: Metro Transit / Metropolitan Council". |
| SHA-256 | see `SHA256SUMS` |

## Profile (2026-10-06, a Tuesday)

| Metric | Value |
| --- | --- |
| Agencies | 8 (Metro Transit, Met Council, Maple Grove, Plymouth, SouthWest Transit, Airport MAC, University of Minnesota) |
| Routes | 127 (124 bus, 3 light rail) |
| Stops | 8,155 |
| Trips (whole feed / on sample day) | 20,220 / 8,028 |
| `stop_times` rows | 872,717 |
| `direction_id`, `shape_id`, `block_id` coverage | 100% / 100% / 100% |
| `shape_dist_traveled` coverage in `stop_times` | 100% |
| Peak trips in progress | 472 at 16:43 |
| Peak vehicles in service (blocks) | 606 at 16:38 (Sat 396, Sun 353) |
| Extra files | `vehicles.txt` (fleet with seated/standing capacity), `feed_info.txt` |
| Stop bounding box (lon/lat) | -93.730, 44.707, -92.806, 45.330 |

Re-run the profile with:

```sh
python3 profile_feed.py metrotransit-mn-20260926.zip
```
