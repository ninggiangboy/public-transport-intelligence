package dev.pti.analytics.retention.application.port;

import dev.pti.analytics.retention.domain.RetentionCutoff;
import dev.pti.analytics.retention.domain.RetentionTarget;

/** Deletes expired rows of {@code insight}, a batch at a time, in the transaction of the caller (DOC-18 §5). */
public interface InsightRetentionStore {

    /**
     * Deletes at most {@code limit} expired rows of the table, so that no statement holds locks for long.
     *
     * @return how many rows it deleted; fewer than {@code limit} means the table has no more expired rows
     */
    int deleteExpired(RetentionTarget target, RetentionCutoff cutoff, int limit);
}
