-- E-52: the progress of a running replay, from the step that is running now (else the newest one). The counters are
-- Spring Batch's, updated at each chunk commit; the moment they were read is the only time the view can give.
SELECT step_name, read_count, write_count,
       read_skip_count + process_skip_count + write_skip_count AS skip_count,
       now() AS read_at
FROM ops.ops_job_step_v
WHERE job_execution_id = :jobExecutionId
ORDER BY (status IN ('STARTING', 'STARTED')) DESC, step_execution_id DESC
LIMIT 1
