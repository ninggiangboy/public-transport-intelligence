package dev.pti.etl.replay.domain;

import dev.pti.etl.core.domain.EtlSource;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A replay a pod claimed: already {@code RUNNING} (DOC-22 §6, ADR-0013). */
public record ReplayRequest(
        UUID id,
        Kind kind,
        EtlSource source,
        @Nullable Instant fromTs,
        @Nullable Instant toTs,
        boolean recomputeAnalytics) {

    public enum Kind {
        RAW_RANGE,
        DLQ_RECORD
    }
}
