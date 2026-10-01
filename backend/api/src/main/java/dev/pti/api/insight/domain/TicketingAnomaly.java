package dev.pti.api.insight.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One ticketing anomaly of a sale point and 15-minute window (DOC-32 E-15, E-16). {@code summary} (JSON text without
 * personal data, DOC-23 §9.5) and {@code batchId} are only read for the detail.
 */
public record TicketingAnomaly(
        UUID id,
        String salePointId,
        @Nullable String salePointName,
        @Nullable String routeId,
        Instant windowStart,
        Instant windowEnd,
        Instant detectedAt,
        String trigger,
        int txnCount,
        int refundCount,
        BigDecimal refundRatio,
        BigDecimal amountSum,
        @Nullable BigDecimal baselineMean,
        @Nullable BigDecimal baselineStddev,
        @Nullable BigDecimal zScore,
        String enrichmentStatus,
        @Nullable String category,
        @Nullable BigDecimal categoryConfidence,
        @Nullable Integer severity,
        @Nullable BigDecimal severityConfidence,
        @Nullable String modelVersion,
        @Nullable String summary,
        @Nullable UUID batchId) {}
