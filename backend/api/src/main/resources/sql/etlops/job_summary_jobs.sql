-- E-31: batch job executions of the window by Spring Batch status.
SELECT status, count(*) AS n
FROM ops.ops_job_run_v
WHERE kind = 'BATCH_JOB'
  AND started_at >= CAST(:from AS timestamptz) AND started_at < CAST(:to AS timestamptz)
GROUP BY status
