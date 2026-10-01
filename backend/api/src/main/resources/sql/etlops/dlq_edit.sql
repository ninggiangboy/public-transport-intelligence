-- E-43: store the edited payload of a dead letter that is in one of the editable statuses. The status stays; raw_payload
-- is never written (the role has no UPDATE on it). Locked and checked as in dlq_transition.sql.
WITH old AS (
  SELECT id, status, source, category_confidence
  FROM ops.dead_letter
  WHERE id = CAST(:id AS uuid) AND status = ANY(CAST(:fromStatuses AS text[]))
  FOR UPDATE
)
UPDATE ops.dead_letter d
SET edited_payload = CAST(:payload AS jsonb),
    updated_at = now()
FROM old
WHERE d.id = old.id
RETURNING old.status AS previous_status, old.source AS source, old.category_confidence AS category_confidence
