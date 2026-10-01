package dev.pti.api.etlops.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** One dead letter in full (DOC-32 E-42): the row, its action log, its replays and what the caller may do now. */
public record DeadLetterDetail(
        DeadLetterItem item,
        String rawPayload,
        @Nullable Map<String, Object> editedPayload,
        @Nullable Kafka kafka,
        UUID batchId,
        @Nullable String modelVersion,
        @Nullable Instant triagedAt,
        int triageAttempts,
        @Nullable Instant lastReplayAt,
        @Nullable String resolvedBy,
        @Nullable Instant resolvedAt,
        List<Action> actions,
        List<ReplayRef> replays,
        List<String> allowedActions) {

    public DeadLetterDetail {
        actions = List.copyOf(actions);
        replays = List.copyOf(replays);
        allowedActions = List.copyOf(allowedActions);
    }

    /** Where the record came from in Kafka. */
    public record Kafka(
            String topic,
            int partition,
            long offset,
            @Nullable Instant timestamp) {}

    /** A line of {@code dlq_action_log}. */
    public record Action(
            Instant at,
            String action,
            String actor,
            @Nullable BigDecimal confidence,
            Map<String, Object> details) {}

    /** A {@code DLQ_RECORD} replay of the dead letter. */
    public record ReplayRef(
            UUID id,
            String status,
            String requestedBy,
            Instant requestedAt,
            @Nullable Instant finishedAt) {}

    public DeadLetterStatus status() {
        return item.status();
    }

    public DeadLetterDetail withAllowedActions(List<String> actions) {
        return new DeadLetterDetail(
                item,
                rawPayload,
                editedPayload,
                kafka,
                batchId,
                modelVersion,
                triagedAt,
                triageAttempts,
                lastReplayAt,
                resolvedBy,
                resolvedAt,
                this.actions,
                replays,
                actions);
    }
}
