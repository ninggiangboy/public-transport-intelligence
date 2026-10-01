-- E-37: a batch id that is a step execution, with the job it belongs to and the replay that started the job, if any.
-- The step view is read first and the run view by job_execution_id (a primary key lookup), because api_reader reads
-- neither batch.* nor ops.etl_batch_step directly.
SELECT s.step_execution_id, s.step_name, s.job_execution_id, r.name AS job_name,
       s.started_at, s.ended_at, s.read_count, s.write_count,
       s.read_skip_count + s.process_skip_count + s.write_skip_count AS skip_count,
       (SELECT rr.id FROM ops.replay_request rr WHERE rr.job_execution_id = s.job_execution_id LIMIT 1)
         AS replay_request_id
FROM ops.ops_job_step_v s
JOIN ops.ops_job_run_v r ON r.kind = 'BATCH_JOB' AND r.job_execution_id = s.job_execution_id
WHERE s.batch_id = CAST(:batchId AS uuid)
