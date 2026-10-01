-- E-30 GET /etl/jobs: the runs started in a window, batch jobs and minutes of micro-batches, newest first. The
-- started_at condition reaches both branches of the view: the job branch uses batch_job_execution_start_idx, the stream
-- branch etl_stream_batch_minute_idx (DOC-15 §5). batch_ids is cut to 20, batch_count says how many there are.
SELECT run_id, kind, name, status, exit_code, exit_message, started_at, ended_at,
       read_count, write_count, skip_count, duplicate_count, job_execution_id,
       batch_ids[1 : 20] AS batch_ids, coalesce(cardinality(batch_ids), 0) AS batch_count
FROM ops.ops_job_run_v
WHERE started_at >= CAST(:from AS timestamptz) AND started_at < CAST(:to AS timestamptz)
  AND (CAST(:kind AS text) IS NULL OR kind = CAST(:kind AS text))
  AND (cardinality(CAST(:names AS text[])) = 0 OR name = ANY(CAST(:names AS text[])))
  AND (cardinality(CAST(:statuses AS text[])) = 0 OR status = ANY(CAST(:statuses AS text[])))
  AND (CAST(:cursorTs AS timestamptz) IS NULL
       OR (started_at, run_id) < (CAST(:cursorTs AS timestamptz), CAST(:cursorRunId AS text)))
ORDER BY started_at DESC, run_id DESC
LIMIT :limit
