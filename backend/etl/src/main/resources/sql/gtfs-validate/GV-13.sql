-- name: GV-13 (DOC-21 §4), warning. A stop (location_type 0) at (0, 0) or more than 200 km from the centre of the
-- stops' bounding box (equirectangular distance, precise enough at this scale).
WITH c AS (
  SELECT (min(lat) + max(lat)) / 2 AS lat, (min(lon) + max(lon)) / 2 AS lon
  FROM dw.dim_stop
  WHERE feed_version_id = :feedVersionId AND location_type = 0 AND NOT (lat = 0 AND lon = 0)
)
SELECT count(*) OVER () AS total,
       jsonb_build_object('stop_id', s.stop_id, 'lat', s.lat, 'lon', s.lon,
                          'message', 'The stop is at (0, 0) or far from the other stops')::text AS sample
FROM dw.dim_stop s, c
WHERE s.feed_version_id = :feedVersionId AND s.location_type = 0
  AND ((s.lat = 0 AND s.lon = 0)
       OR 111.32 * sqrt(power(s.lat - c.lat, 2) + power((s.lon - c.lon) * cos(radians(c.lat)), 2)) > 200)
ORDER BY s.stop_id
LIMIT 20
