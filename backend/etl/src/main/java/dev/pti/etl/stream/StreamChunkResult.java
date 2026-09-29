package dev.pti.etl.stream;

import dev.pti.etl.core.EtlSource;
import dev.pti.etl.write.WriteMode;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** What one committed poll did (DOC-19 §6.1). Counts are messages. */
public record StreamChunkResult(
        UUID batchId,
        EtlSource source,
        WriteMode writeMode,
        int read,
        int written,
        int skipped,
        int duplicate,
        @Nullable Instant minEventTs,
        @Nullable Instant maxEventTs,
        @Nullable Instant minRecordTs,
        Set<String> routeIds) {

    public StreamChunkResult {
        routeIds = Set.copyOf(routeIds);
    }

    public BatchStatus status() {
        return skipped == 0 ? BatchStatus.COMPLETED : BatchStatus.COMPLETED_WITH_SKIPS;
    }

    /** {@code ops.etl_stream_batch.status}. */
    public enum BatchStatus {
        COMPLETED,
        COMPLETED_WITH_SKIPS,
        FAILED
    }
}
