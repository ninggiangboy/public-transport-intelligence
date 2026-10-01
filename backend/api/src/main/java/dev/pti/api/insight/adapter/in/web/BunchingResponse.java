package dev.pti.api.insight.adapter.in.web;

import dev.pti.api.insight.domain.BunchingEpisode;
import dev.pti.api.insight.domain.SuggestionRef;
import dev.pti.api.platform.domain.ApiTime;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A bunching episode of the list (DOC-32 E-10). An open episode has no {@code episodeEnd} and no {@code closeReason};
 * {@code suggestion} is there once the episode has a dispatch suggestion.
 */
public record BunchingResponse(
        UUID id,
        String routeId,
        int directionId,
        String vehicleLeader,
        String vehicleFollower,
        String episodeStart,
        @Nullable String episodeEnd,
        String status,
        @Nullable String closeReason,
        int scheduledHeadwaySeconds,
        int thresholdSeconds,
        int minGapSeconds,
        int lastGapSeconds,
        @Nullable String openStopId,
        int evaluationCount,
        String lastEvaluatedAt,
        @Nullable SuggestionSummary suggestion) {

    /** The short suggestion of a list row: its id, the action and how sure the model is. */
    public record SuggestionSummary(UUID id, String action, BigDecimal actionConfidence) {}

    static BunchingResponse from(BunchingEpisode episode) {
        SuggestionRef suggestion = episode.suggestion();
        return new BunchingResponse(
                episode.id(),
                episode.routeId(),
                episode.directionId(),
                episode.vehicleLeader(),
                episode.vehicleFollower(),
                ApiTime.format(episode.episodeStart()),
                ApiTime.formatNullable(episode.episodeEnd()),
                episode.status(),
                episode.closeReason(),
                episode.scheduledHeadwaySeconds(),
                episode.thresholdSeconds(),
                episode.minGapSeconds(),
                episode.lastGapSeconds(),
                episode.openStopId(),
                episode.evaluationCount(),
                ApiTime.format(episode.lastEvaluatedAt()),
                suggestion == null
                        ? null
                        : new SuggestionSummary(suggestion.id(), suggestion.action(), suggestion.actionConfidence()));
    }
}
