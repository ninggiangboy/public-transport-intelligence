-- E-52 and the read-back of E-44, E-45, E-50: one replay request.
SELECT id, kind, source, from_ts, to_ts, dead_letter_id, recompute_analytics, requested_by, idempotency_key,
       requested_at, status, job_execution_id, started_at, finished_at, CAST(stats AS text) AS stats, message
FROM ops.replay_request
WHERE id = CAST(:id AS uuid)
