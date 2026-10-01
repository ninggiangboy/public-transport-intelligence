-- name: disruption_list
-- Disruption episodes that intersect [:from, :to) with the audience and severity of their alert (DOC-32 E-12).
-- Every episode has an alert, written in the same transaction (DOC-23 §10.2), so the join loses no row. The public view
-- keeps the episodes whose alert is still PUBLIC (FR-09.5 moves one to ENGINEERING). Uses alert_event_dedup_uk.
SELECT d.id, d.route_id, d.direction_id, d.episode_start, d.episode_end, d.status,
       a.severity, a.audience,
       d.baseline_mean_seconds, d.baseline_stddev_seconds, d.current_avg_delay_seconds, d.current_z_score,
       d.peak_avg_delay_seconds, d.peak_z_score, d.sample_count, d.affected_stop_ids, d.last_bucket,
       d.enrichment_status, d.data_issue_probability, d.likely_cause, d.cause_confidence, d.model_version
FROM insight.insight_service_disruption d
JOIN ops.alert_event a ON a.dedup_key = 'disruption:' || d.id::text
WHERE d.episode_start >= :from - interval '3 hours' AND d.episode_start < :to
  AND coalesce(d.episode_end, 'infinity') >= :from
  AND (cardinality(:routeIds::text[]) = 0 OR d.route_id = ANY(:routeIds))
  AND (:status::text IS NULL OR d.status = :status)
  AND (NOT :publicOnly OR a.audience = 'PUBLIC')
  AND (:cursorTs::timestamptz IS NULL OR (d.episode_start, d.id) < (:cursorTs::timestamptz, :cursorId::uuid))
ORDER BY d.episode_start DESC, d.id DESC
LIMIT :limit
