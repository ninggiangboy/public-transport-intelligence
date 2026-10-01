-- name: ticketing_get
-- One ticketing anomaly with its PII-free window summary and batch (DOC-32 E-16).
SELECT t.id, t.sale_point_id, sp.name AS sale_point_name, sp.route_id, t.window_start, t.window_end, t.detected_at,
       t.trigger, t.txn_count, t.refund_count, t.refund_ratio, t.amount_sum, t.baseline_mean, t.baseline_stddev,
       t.z_score, t.enrichment_status, t.category, t.category_confidence, t.severity, t.severity_confidence,
       t.model_version, t.summary::text AS summary, t.batch_id
FROM insight.insight_ticketing_anomaly t
LEFT JOIN dw.dim_sale_point sp ON sp.sale_point_id = t.sale_point_id
WHERE t.id = :id
