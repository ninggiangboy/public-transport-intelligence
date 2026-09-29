-- name: DQ-21 (DOC-16 §3). Rows in a DEFAULT partition: partition maintenance did not run, or a date is far off.
WITH d AS (
  SELECT 'fact_vehicle_position_default' AS tbl, count(*) AS n FROM dw.fact_vehicle_position_default
  UNION ALL
  SELECT 'fact_trip_update_default', count(*) FROM dw.fact_trip_update_default
  UNION ALL
  SELECT 'fact_ticket_sales_default', count(*) FROM dw.fact_ticket_sales_default
)
SELECT (SELECT sum(n) FROM d)::bigint AS violation_count,
       NULL::bigint AS population,
       (SELECT coalesce(jsonb_agg(jsonb_build_object('table', tbl, 'rows', n)), '[]'::jsonb)
        FROM d WHERE n > 0)::text AS sample
