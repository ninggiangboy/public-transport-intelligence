-- E-32: the request that started a job execution: a replay first, else a job request that is not a stop (a stop
-- records the execution it stops). Both tables are small (DOC-18 retention), so no index on job_execution_id.
SELECT type, id
FROM (SELECT 'replay' AS type, id, 1 AS rank, requested_at
      FROM ops.replay_request WHERE job_execution_id = :jobExecutionId
      UNION ALL
      SELECT 'job', id, 2, requested_at
      FROM ops.job_request WHERE job_execution_id = :jobExecutionId AND kind <> 'STOP') requests
ORDER BY rank, requested_at DESC
LIMIT 1
