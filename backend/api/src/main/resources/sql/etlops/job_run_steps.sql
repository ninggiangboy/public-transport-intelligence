-- E-32: the step executions of a job execution, oldest first. short_context is already cut to 2,500 characters.
SELECT step_execution_id, step_name, status, exit_code, exit_message, started_at, ended_at,
       read_count, write_count, filter_count, read_skip_count, process_skip_count, write_skip_count,
       commit_count, rollback_count, batch_id, short_context
FROM ops.ops_job_step_v
WHERE job_execution_id = :jobExecutionId
ORDER BY step_execution_id
