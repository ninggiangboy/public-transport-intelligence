-- E-32: the request that started a job execution: a replay first, else a job request that is not a stop (a stop
-- records the execution it stops). Both tables are small (DOC-18 retention), so no index on job_execution_id.
-- job_runs.sql looks the request up the same way for every batch job of a page.
SELECT type, id, requested_by
FROM (SELECT 'replay' AS type, id, requested_by, 1 AS rank, requested_at
      FROM ops.replay_request WHERE job_execution_id = :jobExecutionId
      UNION ALL
      SELECT 'job', id, requested_by, 2, requested_at
      FROM ops.job_request WHERE job_execution_id = :jobExecutionId AND kind <> 'STOP') requests
ORDER BY rank, requested_at DESC
LIMIT 1
