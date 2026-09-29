-- name: DQ-20 (DOC-16 §3). Fact rows of the last 24 hours whose batch_id is in neither ops.etl_stream_batch nor
-- ops.etl_batch_step (DR-63). Distinct batch ids first, so the anti-join runs once per batch, not per row.
-- Every post-write query returns one row: violation_count, population (NULL unless the threshold is relative) and
-- sample (JSON text, at most 10 offending keys).
WITH recent AS (
  SELECT 'fact_vehicle_position' AS tbl, batch_id FROM dw.fact_vehicle_position
  WHERE service_date >= CAST(:today AS date) - 2 AND ingested_at >= CAST(:realNow AS timestamptz) - interval '24 hours'
  UNION ALL
  SELECT 'fact_trip_update', batch_id FROM dw.fact_trip_update
  WHERE service_date >= CAST(:today AS date) - 2 AND ingested_at >= CAST(:realNow AS timestamptz) - interval '24 hours'
  UNION ALL
  SELECT 'fact_ticket_sales', batch_id FROM dw.fact_ticket_sales
  WHERE sale_date >= CAST(:today AS date) - 2 AND ingested_at >= CAST(:realNow AS timestamptz) - interval '24 hours'
),
per_batch AS (
  SELECT tbl, batch_id, count(*) AS n FROM recent GROUP BY tbl, batch_id
),
orphans AS (
  SELECT p.* FROM per_batch p
  WHERE NOT EXISTS (SELECT 1 FROM ops.etl_stream_batch b WHERE b.batch_id = p.batch_id)
    AND NOT EXISTS (SELECT 1 FROM ops.etl_batch_step s WHERE s.batch_id = p.batch_id)
)
SELECT coalesce((SELECT sum(n) FROM orphans), 0)::bigint AS violation_count,
       NULL::bigint AS population,
       (SELECT coalesce(jsonb_agg(jsonb_build_object('table', tbl, 'batch_id', batch_id, 'rows', n)), '[]'::jsonb)
        FROM (SELECT * FROM orphans ORDER BY n DESC, batch_id LIMIT 10) o)::text AS sample
