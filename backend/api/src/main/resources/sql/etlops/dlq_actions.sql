-- E-48 GET /etl/dlq/actions: the action log with the source and status of each dead letter, newest first, keyset
-- (at, id). dlq_action_log_at_idx serves the window, dlq_action_log_dead_letter_idx a dead letter filter. actorType
-- maps to actor = 'auto', actor LIKE 'system:%' or actor LIKE 'user:%'.
SELECT l.id, l.dead_letter_id, l.action, l.actor, l.confidence, CAST(l.details AS text) AS details, l.at,
       d.source, d.status
FROM ops.dlq_action_log l
JOIN ops.dead_letter d ON d.id = l.dead_letter_id
WHERE l.at >= CAST(:from AS timestamptz) AND l.at < CAST(:to AS timestamptz)
  AND (cardinality(CAST(:actions AS text[])) = 0 OR l.action = ANY(CAST(:actions AS text[])))
  AND (CAST(:deadLetterId AS uuid) IS NULL OR l.dead_letter_id = CAST(:deadLetterId AS uuid))
  AND (CAST(:actorType AS text) IS NULL
       OR (CAST(:actorType AS text) = 'auto' AND l.actor = 'auto')
       OR (CAST(:actorType AS text) = 'system' AND l.actor LIKE 'system:%')
       OR (CAST(:actorType AS text) = 'user' AND l.actor LIKE 'user:%'))
  AND (CAST(:cursorTs AS timestamptz) IS NULL
       OR (l.at, l.id) < (CAST(:cursorTs AS timestamptz), CAST(:cursorId AS bigint)))
ORDER BY l.at DESC, l.id DESC
LIMIT :limit
