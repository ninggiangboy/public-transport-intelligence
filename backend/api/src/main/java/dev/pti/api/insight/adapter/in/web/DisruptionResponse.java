package dev.pti.api.insight.adapter.in.web;

import dev.pti.api.insight.domain.DisruptionEpisode;
import dev.pti.api.platform.domain.ApiTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A disruption episode (DOC-32 E-12, E-13) in one of two views. The <b>public</b> view, which an anonymous caller gets,
 * has only {@code id}, {@code routeId}, {@code directionId}, {@code episodeStart}, {@code episodeEnd}, {@code status},
 * {@code currentAvgDelaySeconds}, {@code peakAvgDelaySeconds}, {@code affectedStopIds} and {@code severity}: no
 * baseline, z-score or AI field, and no audience. The full view, for a viewer, has all the members; {@code closeReason},
 * {@code batchId} and {@code enrichedAt} only in the detail.
 */
public record DisruptionResponse(
        UUID id,
        String routeId,
        int directionId,
        String episodeStart,
        @Nullable String episodeEnd,
        String status,
        int severity,
        @Nullable String audience,
        @Nullable BigDecimal baselineMeanSeconds,
        @Nullable BigDecimal baselineStddevSeconds,
        BigDecimal currentAvgDelaySeconds,
        @Nullable BigDecimal currentZScore,
        BigDecimal peakAvgDelaySeconds,
        @Nullable BigDecimal peakZScore,
        @Nullable Integer sampleCount,
        List<String> affectedStopIds,
        @Nullable String lastBucket,
        @Nullable String enrichmentStatus,
        @Nullable BigDecimal dataIssueProbability,
        @Nullable String likelyCause,
        @Nullable BigDecimal causeConfidence,
        @Nullable String modelVersion,
        @Nullable String closeReason,
        @Nullable UUID batchId,
        @Nullable String enrichedAt) {

    /** What an anonymous caller may see. */
    static DisruptionResponse publicView(DisruptionEpisode episode) {
        return new DisruptionResponse(
                episode.id(),
                episode.routeId(),
                episode.directionId(),
                ApiTime.format(episode.episodeStart()),
                ApiTime.formatNullable(episode.episodeEnd()),
                episode.status(),
                episode.severity(),
                null,
                null,
                null,
                episode.currentAvgDelaySeconds(),
                null,
                episode.peakAvgDelaySeconds(),
                null,
                null,
                episode.affectedStopIds(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /** What a viewer sees; the members of the detail are present when the episode was read as a detail. */
    static DisruptionResponse fullView(DisruptionEpisode episode) {
        return new DisruptionResponse(
                episode.id(),
                episode.routeId(),
                episode.directionId(),
                ApiTime.format(episode.episodeStart()),
                ApiTime.formatNullable(episode.episodeEnd()),
                episode.status(),
                episode.severity(),
                episode.audience(),
                episode.baselineMeanSeconds(),
                episode.baselineStddevSeconds(),
                episode.currentAvgDelaySeconds(),
                episode.currentZScore(),
                episode.peakAvgDelaySeconds(),
                episode.peakZScore(),
                episode.sampleCount(),
                episode.affectedStopIds(),
                ApiTime.format(episode.lastBucket()),
                episode.enrichmentStatus(),
                episode.dataIssueProbability(),
                episode.likelyCause(),
                episode.causeConfidence(),
                episode.modelVersion(),
                episode.closeReason(),
                episode.batchId(),
                ApiTime.formatNullable(episode.enrichedAt()));
    }
}
