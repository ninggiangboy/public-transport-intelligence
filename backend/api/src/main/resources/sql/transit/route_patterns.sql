-- E-02 GET /routes/{routeId}: one pattern per direction. The shape with the most trips wins (the smaller shape_id on a
-- tie); the representative trip is the trip of that shape with the most stop times (the smaller trip_id on a tie).
WITH t AS (
  SELECT trip_id, direction_id, shape_id, direction_label, trip_headsign
  FROM dw.gtfs_trip
  WHERE feed_version_id = :fv AND route_id = :routeId          -- gtfs_trip_route_idx
),
shape_rank AS (
  SELECT DISTINCT ON (direction_id) direction_id, shape_id, count(*) AS trip_count
  FROM t GROUP BY direction_id, shape_id
  ORDER BY direction_id, count(*) DESC, shape_id NULLS LAST
),
rep AS (
  SELECT DISTINCT ON (t.direction_id) t.direction_id, t.trip_id, n.stop_count
  FROM t
  JOIN shape_rank s ON s.direction_id = t.direction_id AND s.shape_id IS NOT DISTINCT FROM t.shape_id
  CROSS JOIN LATERAL (SELECT count(*) AS stop_count FROM dw.gtfs_stop_time st
                      WHERE st.feed_version_id = :fv AND st.trip_id = t.trip_id) n
  ORDER BY t.direction_id, n.stop_count DESC, t.trip_id
)
SELECT s.direction_id, s.shape_id, s.trip_count, r.trip_id AS representative_trip_id,
       (SELECT mode() WITHIN GROUP (ORDER BY direction_label) FROM t
         WHERE t.direction_id = s.direction_id AND t.shape_id IS NOT DISTINCT FROM s.shape_id) AS label,
       (SELECT mode() WITHIN GROUP (ORDER BY trip_headsign) FROM t
         WHERE t.direction_id = s.direction_id AND t.shape_id IS NOT DISTINCT FROM s.shape_id) AS headsign
FROM shape_rank s JOIN rep r USING (direction_id)
ORDER BY s.direction_id
