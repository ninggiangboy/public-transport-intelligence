-- name: DQ-24 (DOC-16 §3). The invariant of DR-13: an observed stop cannot be in the future of its event time.
WITH bad AS (
  SELECT service_date, trip_id, stop_sequence, coalesce(arrival_time, departure_time) AS at, event_timestamp
  FROM dw.fact_trip_update
  WHERE service_date IN (CAST(:today AS date), CAST(:today AS date) - 1)
    AND is_observed
    AND coalesce(arrival_time, departure_time) > event_timestamp
)
SELECT (SELECT count(*) FROM bad) AS violation_count,
       NULL::bigint AS population,
       (SELECT coalesce(jsonb_agg(to_jsonb(b)), '[]'::jsonb)
        FROM (SELECT * FROM bad ORDER BY service_date, trip_id, stop_sequence LIMIT 10) b)::text AS sample
