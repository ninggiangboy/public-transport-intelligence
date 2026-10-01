-- E-33, E-35, E-36: a PENDING job request for etl-batch's JobRequestPoller (ADR-0013). Nothing is inserted, and no row
-- returned, when the actor already has a request with the same idempotency key; the other unique violations still raise.
INSERT INTO ops.job_request (id, kind, job_name, job_parameters, target_job_execution_id, requested_by,
                             idempotency_key, requested_at)
VALUES (:id, :kind, :jobName, CAST(:jobParameters AS jsonb), :targetJobExecutionId, :requestedBy, :idempotencyKey,
        CAST(:requestedAt AS timestamptz))
ON CONFLICT (requested_by, idempotency_key) DO NOTHING
RETURNING id, kind, job_name, CAST(job_parameters AS text) AS job_parameters, target_job_execution_id, requested_by,
          idempotency_key, requested_at, status, job_execution_id, started_at, finished_at, message
