package dev.pti.analytics.retention.application;

import dev.pti.analytics.retention.application.port.InsightRetentionStore;
import dev.pti.analytics.retention.application.port.RetentionMetrics;
import dev.pti.analytics.retention.domain.RetentionCutoff;
import dev.pti.analytics.retention.domain.RetentionPolicy;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.tx.TransactionRunner;

/**
 * Deletes one batch of expired rows of one {@code insight} table (DOC-23 §12.3). {@code OpsRetentionJob} calls it from a
 * tasklet that repeats until a table has no more expired rows, one batch per transaction.
 */
public final class PurgeInsight {

    private final InsightRetentionStore store;
    private final RetentionMetrics metrics;
    private final TransactionRunner tx;
    private final BusinessClock clock;
    private final RetentionPolicy policy;

    public PurgeInsight(
            InsightRetentionStore store,
            RetentionMetrics metrics,
            TransactionRunner tx,
            BusinessClock clock,
            RetentionPolicy policy) {
        this.store = store;
        this.metrics = metrics;
        this.tx = tx;
        this.clock = clock;
        this.policy = policy;
    }

    /**
     * @return the rows deleted; fewer than the request's batch size means the table has no more expired rows
     */
    public int execute(PurgeInsightRequest request) {
        RetentionCutoff cutoff = policy.cutoff(request.target(), clock.instant(), clock.realNow());
        int deleted = tx.inTransaction(() -> store.deleteExpired(request.target(), cutoff, request.batchSize()));
        metrics.deleted(request.target().table(), deleted);
        return deleted;
    }
}
