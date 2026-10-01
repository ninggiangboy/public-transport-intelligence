package dev.pti.api.etlops.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A line of {@code dlq_action_log} with the source and current status of its dead letter (DOC-32 E-48). */
public record ActionLogItem(
        long id,
        UUID deadLetterId,
        String action,
        String actor,
        @Nullable BigDecimal confidence,
        Map<String, Object> details,
        Instant at,
        String source,
        DeadLetterStatus status) {}
