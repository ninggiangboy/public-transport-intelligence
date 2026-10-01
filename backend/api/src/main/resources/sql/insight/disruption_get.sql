-- name: disruption_get
-- One disruption episode with the audience and severity of its alert, and the columns only the detail shows (DOC-32
-- E-13). Whether the caller may see a non-public one is decided in the use case.
SELECT d.id, d.route_id, d.direction_id, d.episode_start, d.episode_end, d.status,
       a.severity, a.audience,
       d.baseline_mean_seconds, d.baseline_stddev_seconds, d.current_avg_delay_seconds, d.current_z_score,
       d.peak_avg_delay_seconds, d.peak_z_score, d.sample_count, d.affected_stop_ids, d.last_bucket,
       d.enrichment_status, d.data_issue_probability, d.likely_cause, d.cause_confidence, d.model_version,
       d.close_reason, d.batch_id, d.enriched_at
FROM insight.insight_service_disruption d
JOIN ops.alert_event a ON a.dedup_key = 'disruption:' || d.id::text
WHERE d.id = :id
