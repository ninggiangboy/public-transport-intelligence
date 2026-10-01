package dev.pti.api.etlops.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A row of {@code ops.replay_request} (DOC-15 §3, ADR-0013): a replay of one dead letter or of a time range of the raw
 * zone, written by the API and executed by {@code etl-batch}. {@code stats} are the numbers the job wrote when it
 * ended, with the keys as the database has them.
 */
public record ReplayRequest(
        UUID id,
        ReplayKind kind,
        String source,
        @Nullable Instant fromTs,
        @Nullable Instant toTs,
        @Nullable UUID deadLetterId,
        boolean recomputeAnalytics,
        String requestedBy,
        @Nullable String idempotencyKey,
        Instant requestedAt,
        String status,
        @Nullable Long jobExecutionId,
        @Nullable Instant startedAt,
        @Nullable Instant finishedAt,
        @Nullable Map<String, Object> stats,
        @Nullable String message) {

    /** A raw zone replay as it is submitted: {@code PENDING}, nothing executed yet. */
    public static ReplayRequest pendingRange(
            UUID id,
            String source,
            Instant fromTs,
            Instant toTs,
            boolean recomputeAnalytics,
            String requestedBy,
            @Nullable String idempotencyKey,
            Instant requestedAt) {
        return new ReplayRequest(
                id,
                ReplayKind.RAW_RANGE,
                source,
                fromTs,
                toTs,
                null,
                recomputeAnalytics,
                requestedBy,
                idempotencyKey,
                requestedAt,
                "PENDING",
                null,
                null,
                null,
                null,
                null);
    }

    /** A replay of one dead letter as it is submitted. */
    public static ReplayRequest pendingRecord(
            UUID id,
            String source,
            UUID deadLetterId,
            String requestedBy,
            @Nullable String idempotencyKey,
            Instant requestedAt) {
        return new ReplayRequest(
                id,
                ReplayKind.DLQ_RECORD,
                source,
                null,
                null,
                deadLetterId,
                false,
                requestedBy,
                idempotencyKey,
                requestedAt,
                "PENDING",
                null,
                null,
                null,
                null,
                null);
    }

    /** True when the other request asks for the same thing: what an {@code Idempotency-Key} protects (DOC-31 §8). */
    public boolean sameAsk(ReplayRequest other) {
        return kind == other.kind
                && source.equals(other.source)
                && Objects.equals(fromTs, other.fromTs)
                && Objects.equals(toTs, other.toTs)
                && Objects.equals(deadLetterId, other.deadLetterId)
                && recomputeAnalytics == other.recomputeAnalytics;
    }
}
