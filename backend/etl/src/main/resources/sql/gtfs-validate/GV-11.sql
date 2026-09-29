-- name: GV-11 (DOC-21 §4), warning. The feed has expired: valid_to is before the business date (feed_expired).
SELECT count(*) OVER () AS total,
       jsonb_build_object('valid_to', r.valid_to, 'business_date', CAST(:businessToday AS date),
                          'message', 'feed_expired')::text AS sample
FROM (SELECT greatest((SELECT max(end_date) FROM dw.gtfs_calendar WHERE feed_version_id = :feedVersionId),
                      (SELECT max(date) FROM dw.gtfs_calendar_date
                       WHERE feed_version_id = :feedVersionId AND exception_type = 1)) AS valid_to) r
WHERE r.valid_to < CAST(:businessToday AS date)
LIMIT 20
