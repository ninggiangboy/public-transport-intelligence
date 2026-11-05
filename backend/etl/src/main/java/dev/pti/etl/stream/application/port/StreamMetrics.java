package dev.pti.etl.stream.application.port;

import dev.pti.common.error.ErrorKind;
import dev.pti.etl.core.domain.EtlSource;
import dev.pti.etl.stream.application.StreamChunkResult;
import dev.pti.etl.write.domain.WriteMode;
import java.time.Duration;

/**
 * The metrics of one chunk (DOC-28): the batch outcome, the item-by-item scan, the chunk duration, the
 * Kafka-to-commit latency and the error counter. Implemented by {@code stream.adapter.out.metrics} with Micrometer.
 */
public interface StreamMetrics {

    /** {@code pti_etl_stream_batches_total{source,status}}: one per poll that committed or failed. */
    void batchCommitted(EtlSource source, StreamChunkResult.BatchStatus status);

    /** {@code pti_etl_chunk_scan_total{source,mode}}: one per poll that had to scan item by item. */
    void scanRequested(EtlSource source);

    /** {@code pti_etl_chunk_duration_seconds{source,mode,write_mode}}. */
    void chunkDuration(EtlSource source, WriteMode mode, Duration duration);

    /** {@code pti_etl_kafka_to_commit_seconds{source}}: once per record of the poll. */
    void commitLatency(EtlSource source, Duration duration);

    /** {@code pti_errors_total{kind,type}}: counted where the error is handled (DOC-30 §5). */
    void error(ErrorKind kind, Throwable error);
}
