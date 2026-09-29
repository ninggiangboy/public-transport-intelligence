-- name: GV-08 (DOC-21 §4). trips.service_id exists in calendar or calendar_dates.
SELECT count(*) OVER () AS total,
       jsonb_build_object('trip_id', t.trip_id, 'service_id', t.service_id,
                          'message', 'The service_id is in neither calendar.txt nor calendar_dates.txt')::text AS sample
FROM dw.gtfs_trip t
WHERE t.feed_version_id = :feedVersionId
  AND NOT EXISTS (SELECT 1 FROM dw.gtfs_calendar c
                  WHERE c.feed_version_id = t.feed_version_id AND c.service_id = t.service_id)
  AND NOT EXISTS (SELECT 1 FROM dw.gtfs_calendar_date d
                  WHERE d.feed_version_id = t.feed_version_id AND d.service_id = t.service_id)
ORDER BY t.trip_id
LIMIT 20
