-- name: dispatch_list
-- Dispatch suggestions created in [:from, :to) (DOC-32 E-17), newest first. Uses insight_dispatch_suggestion_route_idx
-- when routes are given, else _created_idx. :feedback is accepted, ignored or none (no feedback yet).
SELECT s.id, s.bunching_id, s.route_id, s.action, s.action_confidence, s.model_version, s.created_at,
       s.state_snapshot::text AS state_snapshot, s.operator_feedback, s.feedback_by, s.feedback_at
FROM insight.insight_dispatch_suggestion s
WHERE s.created_at >= :from AND s.created_at < :to
  AND (cardinality(:routeIds::text[]) = 0 OR s.route_id = ANY(:routeIds))
  AND (:bunchingId::uuid IS NULL OR s.bunching_id = :bunchingId::uuid)
  AND (:feedback::text IS NULL
       OR (:feedback::text = 'none' AND s.operator_feedback IS NULL)
       OR s.operator_feedback = :feedback::text)
  AND (:cursorTs::timestamptz IS NULL OR (s.created_at, s.id) < (:cursorTs::timestamptz, :cursorId::uuid))
ORDER BY s.created_at DESC, s.id DESC
LIMIT :limit
