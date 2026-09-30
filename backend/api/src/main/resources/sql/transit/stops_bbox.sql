-- E-06 GET /stops?bbox= and ?routeId=: stops in a map window and/or of a route, keyset-paged by stop id. Either
-- filter may be absent (null): the window is then not applied, and :stopIds (the stops of the route) likewise.
-- About 12,000 stops per feed, so a sequential scan of one feed version is well under 10 ms.
SELECT stop_id, stop_code, stop_name, lat, lon, location_type, wheelchair_boarding
FROM dw.dim_stop
WHERE feed_version_id = :fv AND location_type IN (0, 1)
  AND (CAST(:minLon AS float8) IS NULL
       OR (lon BETWEEN :minLon AND :maxLon AND lat BETWEEN :minLat AND :maxLat))
  AND (CAST(:stopIds AS text[]) IS NULL OR stop_id = ANY(CAST(:stopIds AS text[])))
  AND (CAST(:afterStopId AS text) IS NULL OR stop_id > :afterStopId)
ORDER BY stop_id
LIMIT :limit
