-- name: GV-05 (DOC-21 §4). routes, stops (location_type 0), trips and stop_times each have at least one row.
-- Every GV query returns at most 20 rows (total, sample): total counts all violations, sample is JSON text.
SELECT count(*) OVER () AS total,
       jsonb_build_object('file', c.file, 'message', 'The file has no usable rows')::text AS sample
FROM (VALUES ('routes.txt',     EXISTS (SELECT 1 FROM dw.dim_route      WHERE feed_version_id = :feedVersionId)),
             ('stops.txt',      EXISTS (SELECT 1 FROM dw.dim_stop       WHERE feed_version_id = :feedVersionId
                                                                          AND location_type = 0)),
             ('trips.txt',      EXISTS (SELECT 1 FROM dw.gtfs_trip      WHERE feed_version_id = :feedVersionId)),
             ('stop_times.txt', EXISTS (SELECT 1 FROM dw.gtfs_stop_time WHERE feed_version_id = :feedVersionId)))
     AS c(file, present)
WHERE NOT c.present
ORDER BY c.file
LIMIT 20
