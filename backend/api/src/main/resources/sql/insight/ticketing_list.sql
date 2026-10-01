-- name: ticketing_list
-- Ticketing anomalies detected in [:from, :to) (DOC-32 E-15), newest first, with the name and route of the sale point.
-- Uses insight_ticketing_anomaly_detected_idx. :filterCategory is true when the caller named a category or asked for
-- the unclassified ones.
SELECT t.id, t.sale_point_id, sp.name AS sale_point_name, sp.route_id, t.window_start, t.window_end, t.detected_at,
       t.trigger, t.txn_count, t.refund_count, t.refund_ratio, t.amount_sum, t.baseline_mean, t.baseline_stddev,
       t.z_score, t.enrichment_status, t.category, t.category_confidence, t.severity, t.severity_confidence,
       t.model_version
FROM insight.insight_ticketing_anomaly t
LEFT JOIN dw.dim_sale_point sp ON sp.sale_point_id = t.sale_point_id
WHERE t.detected_at >= :from AND t.detected_at < :to
  AND (:salePointId::text IS NULL OR t.sale_point_id = :salePointId)
  AND (NOT :filterCategory OR t.category = ANY(:categories::text[]) OR (:unclassified AND t.category IS NULL))
  AND (cardinality(:severities::int[]) = 0 OR t.severity = ANY(:severities))
  AND (:trigger::text IS NULL OR t.trigger = :trigger)
  AND (:cursorTs::timestamptz IS NULL OR (t.detected_at, t.id) < (:cursorTs::timestamptz, :cursorId::uuid))
ORDER BY t.detected_at DESC, t.id DESC
LIMIT :limit
