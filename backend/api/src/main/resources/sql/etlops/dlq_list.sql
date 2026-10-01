-- E-40 GET /etl/dlq: dead letters, newest first, keyset (created_at, id). dead_letter_list_idx serves a status filter,
-- dead_letter_source_idx a source filter; the other filters apply to what they return. The payload is never read in
-- full: only its first 200 characters.
SELECT id, source, stage, rule_id, error_class, error_message, status, category, category_confidence, severity,
       severity_confidence, business_key, left(raw_payload, 200) AS payload_preview,
       edited_payload IS NOT NULL AS has_edited_payload, replay_count, auto_replay_count, created_at, updated_at
FROM ops.dead_letter
WHERE (cardinality(CAST(:statuses AS text[])) = 0 OR status = ANY(CAST(:statuses AS text[])))
  AND (cardinality(CAST(:sources AS text[])) = 0 OR CAST(source AS text) = ANY(CAST(:sources AS text[])))
  AND (cardinality(CAST(:stages AS text[])) = 0 OR stage = ANY(CAST(:stages AS text[])))
  AND (cardinality(CAST(:categories AS text[])) = 0
       OR coalesce(category, 'unclassified') = ANY(CAST(:categories AS text[])))
  AND (cardinality(CAST(:severities AS text[])) = 0
       OR coalesce(CAST(severity AS text), 'unclassified') = ANY(CAST(:severities AS text[])))
  AND (cardinality(CAST(:ruleIds AS text[])) = 0 OR rule_id = ANY(CAST(:ruleIds AS text[])))
  AND (CAST(:from AS timestamptz) IS NULL OR created_at >= CAST(:from AS timestamptz))
  AND (CAST(:to AS timestamptz) IS NULL OR created_at < CAST(:to AS timestamptz))
  AND (CAST(:cursorTs AS timestamptz) IS NULL
       OR (created_at, id) < (CAST(:cursorTs AS timestamptz), CAST(:cursorId AS uuid)))
ORDER BY created_at DESC, id DESC
LIMIT :limit
