package dev.pti.analytics.retention.application;

import dev.pti.analytics.retention.domain.RetentionTarget;

/**
 * @param target the table to delete from
 * @param batchSize the most rows to delete in this call
 */
public record PurgeInsightRequest(RetentionTarget target, int batchSize) {

    public PurgeInsightRequest {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive: " + batchSize);
        }
    }
}
