-- name: GV-12 (DOC-21 §4), warning. route_type outside {0, 3}: the project only simulates light rail and bus.
SELECT count(*) OVER () AS total,
       jsonb_build_object('route_id', route_id, 'route_type', route_type,
                          'message', 'Only route types 0 and 3 are simulated')::text AS sample
FROM dw.dim_route
WHERE feed_version_id = :feedVersionId AND route_type NOT IN (0, 3)
ORDER BY route_id
LIMIT 20
