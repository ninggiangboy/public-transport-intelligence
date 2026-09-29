-- name: GV-07 (DOC-21 §4). Within a trip, in stop_sequence order, departure_seconds[i] <= arrival_seconds[i+1].
SELECT count(*) OVER () AS total,
       jsonb_build_object('trip_id', s.trip_id, 'stop_sequence', s.stop_sequence,
                          'message', format('Departs at second %s but reaches the next stop at second %s',
                                            s.departure_seconds, s.next_arrival))::text AS sample
FROM (SELECT trip_id, stop_sequence, departure_seconds,
             lead(arrival_seconds) OVER (PARTITION BY trip_id ORDER BY stop_sequence) AS next_arrival
      FROM dw.gtfs_stop_time
      WHERE feed_version_id = :feedVersionId) s
WHERE s.next_arrival < s.departure_seconds
ORDER BY s.trip_id, s.stop_sequence
LIMIT 20
