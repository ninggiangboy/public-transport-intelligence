-- name: DQ-25 (DOC-16 §3). vehicle_position_latest ahead of fact_vehicle_position: the latest row of a vehicle has
-- no fact row with its event time. Vehicles whose partitions were already dropped are not checked.
WITH bad AS (
  SELECT l.vehicle_id, l.service_date, l.event_timestamp
  FROM dw.vehicle_position_latest l
  WHERE l.service_date >= CAST(:today AS date) - 1
    AND NOT EXISTS (SELECT 1 FROM dw.fact_vehicle_position f
                    WHERE f.service_date = l.service_date AND f.vehicle_id = l.vehicle_id
                      AND f.event_timestamp >= l.event_timestamp)
)
SELECT (SELECT count(*) FROM bad) AS violation_count,
       NULL::bigint AS population,
       (SELECT coalesce(jsonb_agg(to_jsonb(b)), '[]'::jsonb)
        FROM (SELECT * FROM bad ORDER BY vehicle_id LIMIT 10) b)::text AS sample
