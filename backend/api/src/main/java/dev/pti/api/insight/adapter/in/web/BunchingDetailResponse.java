package dev.pti.api.insight.adapter.in.web;

import dev.pti.api.insight.domain.BunchingDetail;
import dev.pti.api.insight.domain.BunchingEpisode;
import dev.pti.api.insight.domain.DispatchSuggestion;
import dev.pti.api.platform.domain.ApiTime;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

/**
 * One bunching episode (DOC-32 E-11): as an element of the list, plus the batch, the two trips, the enrichment status
 * and the whole dispatch suggestion.
 */
public record BunchingDetailResponse(
        UUID id,
        String routeId,
        int directionId,
        String vehicleLeader,
        String vehicleFollower,
        String tripLeader,
        String tripFollower,
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
        UUID batchId,
        String enrichmentStatus,
        @Nullable DispatchSuggestionResponse suggestion) {

    static BunchingDetailResponse from(BunchingDetail detail, BigDecimal lowConfidence, JsonMapper json) {
        BunchingEpisode episode = detail.episode();
        DispatchSuggestion suggestion = detail.suggestion();
        return new BunchingDetailResponse(
                episode.id(),
                episode.routeId(),
                episode.directionId(),
                episode.vehicleLeader(),
                episode.vehicleFollower(),
                episode.tripLeader(),
                episode.tripFollower(),
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
                episode.batchId(),
                episode.enrichmentStatus(),
                suggestion == null ? null : DispatchSuggestionResponse.from(suggestion, lowConfidence, json));
    }
}
