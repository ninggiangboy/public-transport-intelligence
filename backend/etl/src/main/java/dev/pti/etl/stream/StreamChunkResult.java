package dev.pti.etl.stream;

import dev.pti.etl.core.EtlSource;
import dev.pti.etl.core.VehiclePositionRow;
import dev.pti.etl.write.WriteMode;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What one committed poll did (DOC-19 §6.1). Counts are messages. {@code positions} are the VehiclePosition rows it
 * wrote, for {@code vehicles.batch} (DOC-20 §8): the UI feed is built from the commit, not by reading them again.
 */
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
        Set<String> routeIds,
        List<VehiclePositionRow> positions) {

    public StreamChunkResult {
        routeIds = Set.copyOf(routeIds);
        positions = List.copyOf(positions);
    }

    /** A result without positions: every source but VehiclePosition, and the tests that do not need them. */
    public StreamChunkResult(
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
        this(
                batchId,
                source,
                writeMode,
                read,
                written,
                skipped,
                duplicate,
                minEventTs,
                maxEventTs,
                minRecordTs,
                routeIds,
                List.of());
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
