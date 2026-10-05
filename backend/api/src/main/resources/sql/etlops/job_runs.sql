-- E-30 GET /etl/jobs: the runs started in a window, batch jobs and minutes of micro-batches, newest first. The
-- started_at condition reaches both branches of the view: the job branch uses batch_job_execution_start_idx, the stream
-- branch etl_stream_batch_minute_idx (DOC-15 §5). batch_ids is cut to 20, batch_count says how many there are.
-- The request of each batch job (the trigger column) is looked up after the LIMIT, as job_run_request.sql does: a
-- replay first, else a job request that is not a stop. Stream runs have none.
SELECT r.*, req.type AS request_type, req.id AS request_id, req.requested_by AS request_by
FROM (SELECT run_id, kind, name, status, exit_code, exit_message, started_at, ended_at,
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
      LIMIT :limit) r
LEFT JOIN LATERAL (
  SELECT type, id, requested_by
  FROM (SELECT 'replay' AS type, id, requested_by, 1 AS rank, requested_at
        FROM ops.replay_request WHERE job_execution_id = r.job_execution_id
        UNION ALL
        SELECT 'job', id, requested_by, 2, requested_at
        FROM ops.job_request WHERE job_execution_id = r.job_execution_id AND kind <> 'STOP') requests
  ORDER BY rank, requested_at DESC
  LIMIT 1
) req ON r.job_execution_id IS NOT NULL
ORDER BY r.started_at DESC, r.run_id DESC
