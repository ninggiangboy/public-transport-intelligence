-- E-44…E-47: move a dead letter to another status, only if it is in one of the allowed ones (DOC-15 §4.3). The old row is
-- read FOR UPDATE: if a triage worker or the ETL moved the dead letter first, the lock wait ends with the status checked
-- again, and nothing is updated. resolved_by and resolved_at are set only when the change closes it. No row returned
-- means the dead letter is missing or was in another status.
WITH old AS (
  SELECT id, status, source, category_confidence
  FROM ops.dead_letter
  WHERE id = CAST(:id AS uuid) AND status = ANY(CAST(:fromStatuses AS text[]))
  FOR UPDATE
)
UPDATE ops.dead_letter d
SET status = CAST(:toStatus AS text),
    updated_at = now(),
    resolved_by = coalesce(CAST(:closedBy AS text), d.resolved_by),
    resolved_at = CASE WHEN CAST(:closedBy AS text) IS NULL THEN d.resolved_at ELSE now() END
FROM old
WHERE d.id = old.id
RETURNING old.status AS previous_status, old.source AS source, old.category_confidence AS category_confidence
