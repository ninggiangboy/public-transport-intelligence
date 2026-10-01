-- E-32: the micro-batches of a stream run, oldest first. date_bin(...) = :minute uses etl_stream_batch_minute_idx.
SELECT batch_id, status, write_mode, instance_id, CAST(offsets AS text) AS offsets,
       records_read, records_written, records_skipped, records_duplicate,
       min_event_ts, max_event_ts, started_at, finished_at, error_class, error_message
FROM ops.etl_stream_batch
WHERE listener_id = :listenerId
  AND date_bin('1 minute', started_at, TIMESTAMPTZ '2000-01-01 00:00:00Z') = CAST(:minute AS timestamptz)
ORDER BY started_at, batch_id
LIMIT :limit
