-- E-42: the last 100 lines of the action log of a dead letter, oldest first (dlq_action_log_dead_letter_idx).
SELECT at, action, actor, confidence, CAST(details AS text) AS details
FROM (SELECT id, at, action, actor, confidence, details
      FROM ops.dlq_action_log
      WHERE dead_letter_id = CAST(:id AS uuid)
      ORDER BY at DESC, id DESC
      LIMIT 100) latest
ORDER BY at, id
