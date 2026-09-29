-- name: GV-10 (DOC-21 §4, DOC-13 §2.5). valid_from/valid_to can be computed, and every day of the week has at
-- least one date in that range with trips (the simulator maps any date onto a day of the feed, DR-08).
WITH r AS (
  SELECT least((SELECT min(start_date) FROM dw.gtfs_calendar WHERE feed_version_id = :feedVersionId),
               (SELECT min(date) FROM dw.gtfs_calendar_date
                WHERE feed_version_id = :feedVersionId AND exception_type = 1)) AS valid_from,
         greatest((SELECT max(end_date) FROM dw.gtfs_calendar WHERE feed_version_id = :feedVersionId),
                  (SELECT max(date) FROM dw.gtfs_calendar_date
                   WHERE feed_version_id = :feedVersionId AND exception_type = 1)) AS valid_to
),
days AS (
  SELECT d::date AS d
  FROM r, generate_series(r.valid_from, least(r.valid_to, r.valid_from + 366), interval '1 day') AS d
),
running AS (
  SELECT DISTINCT extract(isodow FROM days.d)::int AS dow
  FROM days
  WHERE EXISTS (SELECT 1 FROM dw.gtfs_trip t
                WHERE t.feed_version_id = :feedVersionId
                  AND t.service_id IN (SELECT dw.service_ids_on(:feedVersionId, days.d)))
),
violations AS (
  SELECT 0 AS k, jsonb_build_object('message', 'Cannot compute valid_from and valid_to') AS sample
  FROM r WHERE r.valid_from IS NULL OR r.valid_to IS NULL
  UNION ALL
  SELECT w.dow, jsonb_build_object('day_of_week', to_char(date '2024-01-01' + (w.dow - 1), 'FMDay'),
                                   'message', 'No trip runs on this day of the week in the validity range')
  FROM generate_series(1, 7) AS w(dow), r
  WHERE r.valid_from IS NOT NULL AND r.valid_to IS NOT NULL
    AND w.dow NOT IN (SELECT dow FROM running)
)
SELECT count(*) OVER () AS total, sample::text AS sample
FROM violations
ORDER BY k
LIMIT 20
