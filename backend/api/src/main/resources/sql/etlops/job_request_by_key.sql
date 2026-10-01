-- Idempotency (DOC-31 §8): the job request an actor sent with a key. UNIQUE (requested_by, idempotency_key).
SELECT id, kind, job_name, CAST(job_parameters AS text) AS job_parameters, target_job_execution_id, requested_by,
       idempotency_key, requested_at, status, job_execution_id, started_at, finished_at, message
FROM ops.job_request
WHERE requested_by = :requestedBy AND idempotency_key = :key
