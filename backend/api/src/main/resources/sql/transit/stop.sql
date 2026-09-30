-- E-07 GET /stops/{stopId}: the static part of a stop, by primary key.
SELECT stop_id, stop_code, stop_name, lat, lon, location_type, wheelchair_boarding
FROM dw.dim_stop
WHERE feed_version_id = :fv AND stop_id = :stopId AND location_type IN (0, 1)
