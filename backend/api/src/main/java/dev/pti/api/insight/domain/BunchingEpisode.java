package dev.pti.api.insight.domain;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** One bus bunching episode (DOC-32 E-10, E-11); {@code episodeEnd} and {@code closeReason} exist once it is closed. */
public record BunchingEpisode(
        UUID id,
        String routeId,
        int directionId,
        String vehicleLeader,
        String vehicleFollower,
        String tripLeader,
        String tripFollower,
        Instant episodeStart,
        @Nullable Instant episodeEnd,
        String status,
        @Nullable String closeReason,
        int scheduledHeadwaySeconds,
        int thresholdSeconds,
        int minGapSeconds,
        int lastGapSeconds,
        @Nullable String openStopId,
        int evaluationCount,
        Instant lastEvaluatedAt,
        String enrichmentStatus,
        UUID batchId,
        @Nullable SuggestionRef suggestion) {}
