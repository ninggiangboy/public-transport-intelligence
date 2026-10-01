-- E-32, E-35, E-36: one batch job execution from the run view. kind = 'BATCH_JOB' lets the planner drop the stream
-- branch of the view, so that this is a primary key lookup and never aggregates the micro-batch log.
SELECT run_id, kind, name, status, exit_code, exit_message, started_at, ended_at,
       read_count, write_count, skip_count, duplicate_count, job_execution_id,
       batch_ids[1 : 20] AS batch_ids, coalesce(cardinality(batch_ids), 0) AS batch_count
FROM ops.ops_job_run_v
WHERE kind = 'BATCH_JOB' AND job_execution_id = :jobExecutionId
