package dev.pti.etl.stream;

import dev.pti.etl.core.EtlSource;
import io.micrometer.tracing.TraceContext;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Published after a poll has committed (DOC-19 §6.2), never before, so listeners need no transactional binding.
 * Analytics and the UI feed (P4) subscribe on their own executors (DR-35).
 *
 * @param committedAt real time at the commit, for {@code pti_analytics_dispatch_delay_seconds} and
 *     {@code pti_ui_commit_to_publish_seconds} (DOC-20 §8)
 * @param pollTrace the trace context of the {@code pti.etl.poll} span, so that work done later on another thread can
 *     link to it (DOC-23 §14.3); {@code null} when tracing is off
 */
public record MicroBatchCommitted(
        StreamChunkResult result,
        Instant committedAt,
        @Nullable TraceContext pollTrace) {

    public MicroBatchCommitted(StreamChunkResult result, Instant committedAt) {
        this(result, committedAt, null);
    }

    public EtlSource source() {
        return result.source();
    }

    public UUID batchId() {
        return result.batchId();
    }

    public Set<String> routeIds() {
        return result.routeIds();
    }

    public @Nullable Instant minEventTs() {
        return result.minEventTs();
    }

    public @Nullable Instant maxEventTs() {
        return result.maxEventTs();
    }

    public @Nullable Instant minRecordTs() {
        return result.minRecordTs();
    }
}
