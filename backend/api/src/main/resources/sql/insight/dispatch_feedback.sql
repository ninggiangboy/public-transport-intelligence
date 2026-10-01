-- name: dispatch_feedback
-- Records the operator's feedback, replacing an earlier one (DOC-32 E-18). The grant of replay_operator covers exactly
-- these three columns (DOC-17). The time is the database clock, as for every audit column.
UPDATE insight.insight_dispatch_suggestion
SET operator_feedback = :feedback, feedback_by = :actor, feedback_at = now()
WHERE id = :id
RETURNING id, bunching_id, route_id, action, action_confidence, model_version, created_at,
          state_snapshot::text AS state_snapshot, operator_feedback, feedback_by, feedback_at
