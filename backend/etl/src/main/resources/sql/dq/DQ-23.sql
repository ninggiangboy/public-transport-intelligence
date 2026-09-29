-- name: DQ-23 (DOC-16 §3). TripUpdate rows of today and yesterday whose (trip_id, stop_sequence) is not in the
-- ACTIVE schedule, or whose stop_id differs from it. The alert threshold is relative: population is the row count.
WITH tu AS (
  SELECT service_date, trip_id, stop_sequence, stop_id
  FROM dw.fact_trip_update
  WHERE service_date IN (CAST(:today AS date), CAST(:today AS date) - 1)
),
bad AS (
  SELECT tu.*, st.stop_id AS scheduled_stop_id
  FROM tu
  LEFT JOIN dw.gtfs_stop_time st
    ON st.feed_version_id = :activeFeedVersionId AND st.trip_id = tu.trip_id AND st.stop_sequence = tu.stop_sequence
  WHERE :activeFeedVersionId <> -1 AND (st.trip_id IS NULL OR st.stop_id <> tu.stop_id)
)
SELECT (SELECT count(*) FROM bad) AS violation_count,
       (SELECT count(*) FROM tu) AS population,
       (SELECT coalesce(jsonb_agg(to_jsonb(b)), '[]'::jsonb)
        FROM (SELECT * FROM bad ORDER BY service_date, trip_id, stop_sequence LIMIT 10) b)::text AS sample
