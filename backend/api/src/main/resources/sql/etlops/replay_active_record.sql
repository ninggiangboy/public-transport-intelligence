-- E-44, E-45: the replay of a dead letter that is waiting or running (replay_request_one_per_record).
SELECT id
FROM ops.replay_request
WHERE kind = 'DLQ_RECORD' AND dead_letter_id = CAST(:deadLetterId AS uuid) AND status IN ('PENDING', 'RUNNING')
LIMIT 1
