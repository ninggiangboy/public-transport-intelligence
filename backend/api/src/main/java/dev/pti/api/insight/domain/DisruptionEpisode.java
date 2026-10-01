package dev.pti.api.insight.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One service disruption episode with the audience and severity of its alert (DOC-32 E-12, E-13). The AI fields are
 * absent until the episode is enriched. {@code closeReason}, {@code batchId} and {@code enrichedAt} are only read for
 * the detail.
 */
public record DisruptionEpisode(
        UUID id,
        String routeId,
        int directionId,
        Instant episodeStart,
        @Nullable Instant episodeEnd,
        String status,
        int severity,
        String audience,
        BigDecimal baselineMeanSeconds,
        BigDecimal baselineStddevSeconds,
        BigDecimal currentAvgDelaySeconds,
        BigDecimal currentZScore,
        BigDecimal peakAvgDelaySeconds,
        BigDecimal peakZScore,
        int sampleCount,
        List<String> affectedStopIds,
        Instant lastBucket,
        String enrichmentStatus,
        @Nullable BigDecimal dataIssueProbability,
        @Nullable String likelyCause,
        @Nullable BigDecimal causeConfidence,
        @Nullable String modelVersion,
        @Nullable String closeReason,
        @Nullable UUID batchId,
        @Nullable Instant enrichedAt) {

    public DisruptionEpisode {
        affectedStopIds = List.copyOf(affectedStopIds);
    }

    /** Whether the alert of the episode is visible to everyone, which is what an anonymous caller may see (ADR-0023). */
    public boolean isPublic() {
        return "PUBLIC".equals(audience);
    }
}
