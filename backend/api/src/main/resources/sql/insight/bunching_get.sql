-- name: bunching_get
-- One bunching episode with its whole dispatch suggestion (DOC-32 E-11). The suggestion columns carry the prefix s_.
SELECT b.id, b.route_id, b.direction_id, b.vehicle_leader, b.vehicle_follower, b.trip_leader, b.trip_follower,
       b.episode_start, b.episode_end, b.status, b.close_reason, b.scheduled_headway_seconds, b.threshold_seconds,
       b.min_gap_seconds, b.last_gap_seconds, b.open_stop_id, b.evaluation_count, b.last_evaluated_at,
       b.enrichment_status, b.batch_id,
       s.id AS s_id, s.bunching_id AS s_bunching_id, s.route_id AS s_route_id, s.action AS s_action,
       s.action_confidence AS s_action_confidence, s.model_version AS s_model_version,
       s.created_at AS s_created_at, s.state_snapshot::text AS s_state_snapshot,
       s.operator_feedback AS s_operator_feedback, s.feedback_by AS s_feedback_by, s.feedback_at AS s_feedback_at
FROM insight.insight_bus_bunching b
LEFT JOIN insight.insight_dispatch_suggestion s ON s.bunching_id = b.id
WHERE b.id = :id
