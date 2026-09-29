-- name: DQ-22 (DOC-16 §3). Refunds whose original sale never arrived. :now is the business clock (DR-67),
-- :graceSeconds is pti.dq.refund-grace.
WITH orphans AS (
  SELECT r.transaction_id, r.refund_of, r.created_at
  FROM dw.fact_ticket_sales r
  WHERE r.txn_type = 'REFUND'
    AND NOT r.is_deleted
    AND r.sale_date >= CAST(:today AS date) - 2
    AND r.created_at >= CAST(:now AS timestamptz) - interval '24 hours'
    AND r.created_at <  CAST(:now AS timestamptz) - make_interval(secs => :graceSeconds)
    AND NOT EXISTS (SELECT 1 FROM dw.fact_ticket_sales s WHERE s.transaction_id = r.refund_of)
)
SELECT (SELECT count(*) FROM orphans) AS violation_count,
       NULL::bigint AS population,
       (SELECT coalesce(jsonb_agg(to_jsonb(o)), '[]'::jsonb)
        FROM (SELECT * FROM orphans ORDER BY created_at LIMIT 10) o)::text AS sample
