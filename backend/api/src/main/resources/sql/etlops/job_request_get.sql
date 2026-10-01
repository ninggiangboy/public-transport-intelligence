-- E-34 and the read-back of E-33, E-35, E-36: one job request.
SELECT id, kind, job_name, CAST(job_parameters AS text) AS job_parameters, target_job_execution_id, requested_by,
       idempotency_key, requested_at, status, job_execution_id, started_at, finished_at, message
FROM ops.job_request
WHERE id = :id
