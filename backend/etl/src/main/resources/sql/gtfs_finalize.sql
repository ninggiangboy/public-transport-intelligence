-- name: gtfs_finalize (DOC-14 §6.1)
-- GtfsStaticLoadJob, step "finalize" (DOC-21). Runs after all files are loaded as STAGED and
-- before the activation step. Three statements, one transaction; :feedVersionId is the STAGED version.

-- (1) feed metadata used by DQ rules
UPDATE dw.gtfs_feed_version f
SET valid_from   = s.valid_from,
    valid_to     = s.valid_to,
    bbox_min_lon = b.min_lon, bbox_min_lat = b.min_lat,
    bbox_max_lon = b.max_lon, bbox_max_lat = b.max_lat
FROM (SELECT least(min(c.start_date), (SELECT min(date) FROM dw.gtfs_calendar_date WHERE feed_version_id = :feedVersionId AND exception_type = 1)) AS valid_from,
             greatest(max(c.end_date), (SELECT max(date) FROM dw.gtfs_calendar_date WHERE feed_version_id = :feedVersionId AND exception_type = 1)) AS valid_to
      FROM dw.gtfs_calendar c WHERE c.feed_version_id = :feedVersionId) s,
     (SELECT min(lon) AS min_lon, min(lat) AS min_lat, max(lon) AS max_lon, max(lat) AS max_lat
      FROM dw.dim_stop WHERE feed_version_id = :feedVersionId AND location_type = 0) b
WHERE f.feed_version_id = :feedVersionId;

-- (2) route_headway (DR-12, adjusted): one representative date per day type; departures at the
--     reference stop = the stop served by the most trips of (route, direction) that day.
INSERT INTO dw.route_headway (feed_version_id, route_id, direction_id, day_type, hour_of_day,
                              scheduled_headway_seconds, trip_count)
WITH rep AS (
  SELECT DISTINCT ON (day_type) day_type, d
  FROM (SELECT d::date AS d,
               CASE extract(isodow FROM d) WHEN 6 THEN 'SATURDAY' WHEN 7 THEN 'SUNDAY_HOLIDAY' ELSE 'WEEKDAY' END AS day_type
        FROM dw.gtfs_feed_version f,
             generate_series(f.valid_from, least(f.valid_from + 27, f.valid_to), interval '1 day') AS d
        WHERE f.feed_version_id = :feedVersionId
          AND extract(isodow FROM d) IN (2, 6, 7)
          AND NOT EXISTS (SELECT 1 FROM dw.gtfs_calendar_date cd
                          WHERE cd.feed_version_id = :feedVersionId AND cd.date = d::date)) x
  ORDER BY day_type, d
),
day_trips AS (
  SELECT rep.day_type, t.trip_id, t.route_id, t.direction_id
  FROM rep
  JOIN dw.gtfs_trip t ON t.feed_version_id = :feedVersionId AND t.service_id IN (SELECT dw.service_ids_on(:feedVersionId, rep.d))
),
stop_use AS (
  SELECT dt.day_type, dt.route_id, dt.direction_id, st.stop_id,
         count(*) AS trips, avg(st.stop_sequence) AS avg_seq
  FROM day_trips dt
  JOIN dw.gtfs_stop_time st ON st.feed_version_id = :feedVersionId AND st.trip_id = dt.trip_id
  GROUP BY dt.day_type, dt.route_id, dt.direction_id, st.stop_id
),
ref_stop AS (
  SELECT DISTINCT ON (day_type, route_id, direction_id) day_type, route_id, direction_id, stop_id
  FROM stop_use
  ORDER BY day_type, route_id, direction_id, trips DESC, avg_seq, stop_id
),
departures AS (
  SELECT dt.day_type, dt.route_id, dt.direction_id, st.departure_seconds AS dep
  FROM day_trips dt
  JOIN ref_stop r USING (day_type, route_id, direction_id)
  JOIN dw.gtfs_stop_time st ON st.feed_version_id = :feedVersionId AND st.trip_id = dt.trip_id AND st.stop_id = r.stop_id
),
gaps AS (
  SELECT day_type, route_id, direction_id, ((dep / 3600) % 24)::smallint AS hour_of_day,
         dep - lag(dep) OVER (PARTITION BY day_type, route_id, direction_id ORDER BY dep) AS gap
  FROM departures
)
SELECT :feedVersionId, route_id, direction_id, day_type, hour_of_day,
       (percentile_disc(0.5) WITHIN GROUP (ORDER BY gap) FILTER (WHERE gap > 0))::int,
       count(*)
FROM gaps
GROUP BY route_id, direction_id, day_type, hour_of_day;

-- (3) display headway: weekday 07-19 median over both directions
UPDATE dw.dim_route r
SET typical_headway_seconds = h.median
FROM (SELECT route_id, percentile_disc(0.5) WITHIN GROUP (ORDER BY scheduled_headway_seconds) AS median
      FROM dw.route_headway
      WHERE feed_version_id = :feedVersionId AND day_type = 'WEEKDAY' AND hour_of_day BETWEEN 7 AND 18
        AND scheduled_headway_seconds IS NOT NULL
      GROUP BY route_id) h
WHERE r.feed_version_id = :feedVersionId AND r.route_id = h.route_id;
