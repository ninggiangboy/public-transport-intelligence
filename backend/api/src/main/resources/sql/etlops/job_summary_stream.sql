-- E-31 GET /etl/jobs/summary: micro-batches per source and bucket. Buckets without a batch are added in Java.
SELECT b.source,
       date_bin(make_interval(secs => CAST(:bucketSeconds AS double precision)), b.started_at,
                TIMESTAMPTZ '2000-01-01 00:00:00Z') AS bucket_start,
       count(*)                                    AS batches,
       count(*) FILTER (WHERE b.status = 'FAILED') AS failed_batches,
       sum(b.records_read)      AS read_count,
       sum(b.records_written)   AS write_count,
       sum(b.records_skipped)   AS skip_count,
       sum(b.records_duplicate) AS duplicate_count,
       CAST(percentile_disc(0.95) WITHIN GROUP (
              ORDER BY extract(epoch FROM b.finished_at - b.started_at) * 1000) AS int) AS p95_batch_ms
FROM ops.etl_stream_batch b
WHERE b.started_at >= CAST(:from AS timestamptz) AND b.started_at < CAST(:to AS timestamptz)
GROUP BY 1, 2
ORDER BY 1, 2
