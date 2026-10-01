-- Idempotency (DOC-31 §8): the replay request an actor sent with a key. UNIQUE (requested_by, idempotency_key).
SELECT id, kind, source, from_ts, to_ts, dead_letter_id, recompute_analytics, requested_by, idempotency_key,
       requested_at, status, job_execution_id, started_at, finished_at, CAST(stats AS text) AS stats, message
FROM ops.replay_request
WHERE requested_by = :requestedBy AND idempotency_key = :key
