-- name: GV-06 (DOC-21 §4). Every trip has at least two stop_times.
SELECT count(*) OVER () AS total,
       jsonb_build_object('trip_id', v.trip_id, 'stop_times', v.stops,
                          'message', 'A trip needs at least two stop times')::text AS sample
FROM (SELECT t.trip_id, count(st.stop_sequence) AS stops
      FROM dw.gtfs_trip t
      LEFT JOIN dw.gtfs_stop_time st ON st.feed_version_id = t.feed_version_id AND st.trip_id = t.trip_id
      WHERE t.feed_version_id = :feedVersionId
      GROUP BY t.trip_id
      HAVING count(st.stop_sequence) < 2) v
ORDER BY v.trip_id
LIMIT 20
