-- E-42: the DLQ_RECORD replays of a dead letter, oldest first.
SELECT id, status, requested_by, requested_at, finished_at
FROM ops.replay_request
WHERE kind = 'DLQ_RECORD' AND dead_letter_id = CAST(:id AS uuid)
ORDER BY requested_at, id
