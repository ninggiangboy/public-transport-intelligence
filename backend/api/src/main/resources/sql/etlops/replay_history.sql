-- E-53 GET /etl/replays/estimate: what the micro-batch log says about a window. The records are those read by batches
-- that started in [from, toPlusLookahead): a record is processed a few seconds after the raw zone got it. A failed
-- attempt is left out of the sum, because a later batch read its records again. The minutes are those of [from, to)
-- that have at least one batch (etl_stream_batch_started_idx).
SELECT coalesce(sum(records_read) FILTER (WHERE status <> 'FAILED'), 0) AS records_read,
       count(*) AS batches,
       count(DISTINCT date_bin('1 minute', started_at, TIMESTAMPTZ '2000-01-01 00:00:00Z'))
         FILTER (WHERE started_at < CAST(:to AS timestamptz)) AS minutes_with_batches
FROM ops.etl_stream_batch
WHERE CAST(source AS text) = :source
  AND started_at >= CAST(:from AS timestamptz) AND started_at < CAST(:toPlusLookahead AS timestamptz)
