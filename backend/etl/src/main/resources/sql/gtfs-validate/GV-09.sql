-- name: GV-09 (DOC-21 §4). A non-null trips.shape_id exists in shapes; at most 1% of trips have no shape_id.
SELECT count(*) OVER () AS total, v.sample
FROM (SELECT t.trip_id AS k,
             jsonb_build_object('trip_id', t.trip_id, 'shape_id', t.shape_id,
                                'message', 'The shape_id is not in shapes.txt')::text AS sample
      FROM dw.gtfs_trip t
      WHERE t.feed_version_id = :feedVersionId
        AND t.shape_id IS NOT NULL
        AND NOT EXISTS (SELECT 1 FROM dw.gtfs_shape s
                        WHERE s.feed_version_id = t.feed_version_id AND s.shape_id = t.shape_id)
      UNION ALL
      SELECT '', jsonb_build_object('trips_without_shape', r.missing, 'trips', r.trips,
                                    'message', 'More than 1% of the trips have no shape_id')::text
      FROM (SELECT count(*) FILTER (WHERE shape_id IS NULL) AS missing, count(*) AS trips
            FROM dw.gtfs_trip WHERE feed_version_id = :feedVersionId) r
      WHERE r.missing > 0.01 * r.trips) v
ORDER BY v.k
LIMIT 20
