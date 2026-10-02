package dev.pti.analytics.recompute.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The business-time range of the records a replay wrote (DOC-23 §11.1), taken from the context of step
 * {@code replayRecords} (DOC-22 §4.4).
 *
 * @param minEventTs smallest {@code event_timestamp} written
 * @param maxEventTs largest {@code event_timestamp} written
 * @param minCreatedAt smallest {@code created_at} of the written ticketing sales; {@code null} for other sources
 * @param maxCreatedAt largest {@code created_at}; {@code null} for other sources
 */
public record ReplayRange(
        Instant minEventTs,
        Instant maxEventTs,
        @Nullable Instant minCreatedAt,
        @Nullable Instant maxCreatedAt) {

    public ReplayRange {
        if (maxEventTs.isBefore(minEventTs)) {
            throw new IllegalArgumentException("The range ends before it starts: " + minEventTs + " .. " + maxEventTs);
        }
    }
}
