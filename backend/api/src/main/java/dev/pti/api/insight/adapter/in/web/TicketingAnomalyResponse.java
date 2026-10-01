package dev.pti.api.insight.adapter.in.web;

import dev.pti.api.insight.domain.TicketingAnomaly;
import dev.pti.api.platform.domain.ApiTime;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A ticketing anomaly (DOC-32 E-15); the detail (E-16) adds {@code summary}, the window summary without personal data,
 * and {@code batchId}. The AI members are absent until the anomaly is enriched; {@code enrichmentStatus} is always
 * there, so the screen can show "Unclassified" (FR-09.7).
 */
public record TicketingAnomalyResponse(
        UUID id,
        String salePointId,
        @Nullable String salePointName,
        @Nullable String routeId,
        String windowStart,
        String windowEnd,
        String detectedAt,
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
        @Nullable JsonNode summary,
        @Nullable UUID batchId) {

    static TicketingAnomalyResponse from(TicketingAnomaly anomaly, JsonMapper json) {
        String summary = anomaly.summary();
        return new TicketingAnomalyResponse(
                anomaly.id(),
                anomaly.salePointId(),
                anomaly.salePointName(),
                anomaly.routeId(),
                ApiTime.format(anomaly.windowStart()),
                ApiTime.format(anomaly.windowEnd()),
                ApiTime.format(anomaly.detectedAt()),
                anomaly.trigger(),
                anomaly.txnCount(),
                anomaly.refundCount(),
                anomaly.refundRatio(),
                anomaly.amountSum(),
                anomaly.baselineMean(),
                anomaly.baselineStddev(),
                anomaly.zScore(),
                anomaly.enrichmentStatus(),
                anomaly.category(),
                anomaly.categoryConfidence(),
                anomaly.severity(),
                anomaly.severityConfidence(),
                anomaly.modelVersion(),
                summary == null ? null : json.readTree(summary),
                anomaly.batchId());
    }
}
