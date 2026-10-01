-- name: dispatch_get
-- One dispatch suggestion, read on the primary before feedback is written (DOC-32 E-18).
SELECT s.id, s.bunching_id, s.route_id, s.action, s.action_confidence, s.model_version, s.created_at,
       s.state_snapshot::text AS state_snapshot, s.operator_feedback, s.feedback_by, s.feedback_at
FROM insight.insight_dispatch_suggestion s
WHERE s.id = :id
