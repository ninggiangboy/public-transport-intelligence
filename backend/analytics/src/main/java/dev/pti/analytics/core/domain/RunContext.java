package dev.pti.analytics.core.domain;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a detector needs to know about the trigger of one {@code advance}, beyond the route (DOC-23 §4, §5.6).
 *
 * <p>DOC-23 §3 passes {@code sourceRecordTs} and {@code committedAt} only, but §5.6 also reads the micro-batch's
 * {@code minEventTs} to place a new cursor, and §14.2 logs the micro-batch id next to the run's own; the context
 * carries all of them.
 *
 * @param batchId the {@code batch_id} of this run: a fresh UUIDv7 for a live run, the step's id for a job
 * @param sourceBatchId the {@code batch_id} of the micro-batch that triggered the run; {@code null} for a tick
 * @param minEventTs smallest event time of the triggering micro-batch; {@code null} for a tick
 * @param sourceRecordTs smallest Kafka record time of the triggering micro-batch (DR-57); {@code null} for a tick
 * @param committedAt real time the micro-batch committed; {@code null} for a tick
 */
public record RunContext(
        UUID batchId,
        @Nullable UUID sourceBatchId,
        @Nullable Instant minEventTs,
        @Nullable Instant sourceRecordTs,
        @Nullable Instant committedAt) {

    /** A run that no micro-batch triggered: the 30 second tick, a job or a recompute. */
    public static RunContext untriggered(UUID batchId) {
        return new RunContext(batchId, null, null, null, null);
    }

    /** A run that follows a committed micro-batch. */
    public static RunContext afterBatch(
            UUID batchId,
            UUID sourceBatchId,
            @Nullable Instant minEventTs,
            @Nullable Instant sourceRecordTs,
            Instant committedAt) {
        return new RunContext(batchId, sourceBatchId, minEventTs, sourceRecordTs, committedAt);
    }
}
