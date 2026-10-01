package dev.pti.api.etlops.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A dead letter as the list shows it (DOC-32 E-40) and as the head of the detail (E-42). {@code payloadPreview} is the
 * first 200 characters of the raw payload and is only read for the list.
 */
public record DeadLetterItem(
        UUID id,
        String source,
        String stage,
        @Nullable String ruleId,
        String errorClass,
        String errorMessage,
        DeadLetterStatus status,
        @Nullable String category,
        @Nullable BigDecimal categoryConfidence,
        @Nullable Integer severity,
        @Nullable BigDecimal severityConfidence,
        @Nullable String businessKey,
        @Nullable String payloadPreview,
        boolean hasEditedPayload,
        int replayCount,
        int autoReplayCount,
        Instant createdAt,
        Instant updatedAt) {}
