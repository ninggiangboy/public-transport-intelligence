-- E-32, E-35, E-36: the micro-batches of one listener in one minute, folded as ops.ops_job_run_v folds them. Written
-- against etl_stream_batch directly, because a filter on the run id of the view cannot use etl_stream_batch_minute_idx;
-- date_bin(...) = :minute can (DOC-32 E-32).
SELECT 'stream:' || sb.listener_id || ':'
         || to_char(date_bin('1 minute', sb.started_at, TIMESTAMPTZ '2000-01-01 00:00:00Z') AT TIME ZONE 'UTC',
                    'YYYY-MM-DD"T"HH24:MI"Z"') AS run_id,
       'STREAM' AS kind,
       sb.listener_id AS name,
       CASE WHEN bool_or(sb.status = 'FAILED')               THEN 'FAILED'
            WHEN bool_or(sb.status = 'COMPLETED_WITH_SKIPS') THEN 'COMPLETED_WITH_SKIPS'
            ELSE 'COMPLETED' END AS status,
       CAST(NULL AS text) AS exit_code,
       CAST(NULL AS text) AS exit_message,
       min(sb.started_at) AS started_at,
       max(sb.finished_at) AS ended_at,
       sum(sb.records_read) AS read_count,
       sum(sb.records_written) AS write_count,
       sum(sb.records_skipped) AS skip_count,
       sum(sb.records_duplicate) AS duplicate_count,
       CAST(NULL AS bigint) AS job_execution_id,
       CAST(NULL AS uuid[]) AS batch_ids,
       count(*) AS batch_count
FROM ops.etl_stream_batch sb
WHERE sb.listener_id = :listenerId
  AND date_bin('1 minute', sb.started_at, TIMESTAMPTZ '2000-01-01 00:00:00Z') = CAST(:minute AS timestamptz)
GROUP BY sb.listener_id, date_bin('1 minute', sb.started_at, TIMESTAMPTZ '2000-01-01 00:00:00Z')
