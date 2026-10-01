-- E-43…E-47: one line of the append-only dlq_action_log, written in the transaction of the change.
INSERT INTO ops.dlq_action_log (dead_letter_id, action, actor, confidence, details)
VALUES (CAST(:id AS uuid), :action, :actor, :confidence, CAST(:details AS jsonb))
