package dev.pti.api.etlops.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Where a {@code batch_id} comes from and what it did (DOC-32 E-37, FR-12.5): a micro-batch of a stream listener or a
 * step of a batch job, the dead letters it made and the data quality checks of it. Which fields are set depends on
 * {@code origin}: a {@code STREAM} has the listener, source, instance, offsets and write mode, a {@code BATCH_STEP}
 * the run, job, step and, for a replay, the replay request.
 */
public record BatchLineage(
        UUID batchId,
        Origin origin,
        @Nullable String runId,
        @Nullable String jobName,
        @Nullable String stepName,
        @Nullable Long stepExecutionId,
        @Nullable String listenerId,
        @Nullable String source,
        @Nullable String instanceId,
        @Nullable Map<String, Object> offsets,
        @Nullable String writeMode,
        @Nullable Instant startedAt,
        @Nullable Instant endedAt,
        Counts counts,
        @Nullable UUID replayRequestId,
        DeadLetters deadLetters,
        List<DataQualityResult> dataQuality) {

    public BatchLineage {
        dataQuality = List.copyOf(dataQuality);
    }

    /** Whether the batch is a streaming micro-batch or a step execution. */
    public enum Origin {
        STREAM,
        BATCH_STEP
    }

    /** The counters of the batch or step. */
    public record Counts(long read, long written, long skipped) {}

    /** The dead letters with this {@code batch_id}, in all and by current status. */
    public record DeadLetters(long total, Map<String, Long> byStatus) {}

    /** One result of {@code ops.dq_check_result} for the batch. */
    public record DataQualityResult(String ruleId, String tableName, long violationCount, Instant checkedAt) {}
}
