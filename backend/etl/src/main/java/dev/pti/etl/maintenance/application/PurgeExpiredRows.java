package dev.pti.etl.maintenance.application;

import dev.pti.etl.maintenance.application.port.ExpiredRows;
import dev.pti.etl.maintenance.application.port.RetentionMetrics;
import java.time.OffsetDateTime;

/** Deletes one batch of expired rows of one table, in the caller's transaction (DOC-18 §5). */
public final class PurgeExpiredRows {

    private final ExpiredRows rows;
    private final RetentionMetrics metrics;

    public PurgeExpiredRows(ExpiredRows rows, RetentionMetrics metrics) {
        this.rows = rows;
        this.metrics = metrics;
    }

    /** @return how many rows it deleted */
    public int purgeOne(PurgeTarget target, OffsetDateTime cutoff, int batchSize) {
        int deleted = rows.deleteBatch(target, cutoff, batchSize);
        metrics.deleted(target.table(), deleted);
        return deleted;
    }
}
