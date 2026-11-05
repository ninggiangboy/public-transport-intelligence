package dev.pti.etl.maintenance.application;

import dev.pti.etl.maintenance.application.port.BatchMetadataStore;
import dev.pti.etl.maintenance.application.port.RetentionMetrics;
import java.time.LocalDateTime;

/** Deletes one batch of Spring Batch metadata older than the retention, in the caller's transaction (DOC-18 §5). */
public final class CleanupBatchMetadata {

    /** The batch metadata table the metric is reported for. */
    static final String TABLE = "batch.batch_job_instance";

    private final BatchMetadataStore metadata;
    private final RetentionMetrics metrics;

    public CleanupBatchMetadata(BatchMetadataStore metadata, RetentionMetrics metrics) {
        this.metadata = metadata;
        this.metrics = metrics;
    }

    /** @return how many job instances it deleted */
    public int cleanupOne(LocalDateTime cutoff, int batchSize) {
        int deleted = metadata.deleteBatch(cutoff, batchSize);
        metrics.deleted(TABLE, deleted);
        return deleted;
    }
}
