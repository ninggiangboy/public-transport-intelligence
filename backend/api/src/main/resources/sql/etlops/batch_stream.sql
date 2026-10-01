-- E-37: a batch id that is a streaming micro-batch (primary key lookup).
SELECT batch_id, listener_id, source, instance_id, CAST(offsets AS text) AS offsets, write_mode,
       records_read, records_written, records_skipped, started_at, finished_at
FROM ops.etl_stream_batch
WHERE batch_id = CAST(:batchId AS uuid)
