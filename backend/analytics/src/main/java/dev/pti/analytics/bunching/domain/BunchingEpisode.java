package dev.pti.analytics.bunching.domain;

import dev.pti.common.id.InsightIds;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A row of {@code insight.insight_bus_bunching} as the detector computes it (DOC-23 §5.7): the columns analytics owns.
 * The enrichment columns are never part of it. An episode is open until {@code closeReason} is set.
 *
 * @param episodeStart event time of the first evaluation below the open ratio
 * @param episodeEnd event time the two vehicles were last evaluated side by side; {@code null} while open
 * @param closeReason {@code null} while open
 * @param scheduledHeadwaySeconds the headway of the hour the episode opened in
 * @param thresholdSeconds {@code floor(open-ratio × headway)} at the moment it opened
 * @param minGapSeconds the smallest gap seen
 * @param openStopId the follower's next stop when the episode began
 * @param evaluationCount how many evaluations the episode covers, the opening ones included
 */
public record BunchingEpisode(
        UUID id,
        String routeId,
        int directionId,
        String leader,
        String follower,
        String leaderTrip,
        String followerTrip,
        Instant episodeStart,
        @Nullable Instant episodeEnd,
        @Nullable CloseReason closeReason,
        int scheduledHeadwaySeconds,
        int thresholdSeconds,
        int minGapSeconds,
        int lastGapSeconds,
        @Nullable String openStopId,
        int evaluationCount,
        Instant lastEvaluatedAt) {

    public BunchingEpisode {
        if ((episodeEnd == null) != (closeReason == null)) {
            throw new IllegalArgumentException("An episode has an end exactly when it has a close reason");
        }
    }

    /**
     * The episode that a pair opens when it has been below the open ratio long enough.
     *
     * @param pending what the pair collected while it was counting
     * @param evaluation the evaluation that made the count reach the limit
     * @param threshold {@code floor(open-ratio × headway)}, at least one
     */
    static BunchingEpisode open(String routeId, PairState pending, Evaluation evaluation, int threshold, Instant tick) {
        Instant start = pending.firstBelowAt();
        if (start == null) {
            throw new IllegalArgumentException("A pair that opens an episode has a first evaluation below the ratio");
        }
        Integer pendingMin = pending.pendingMinGapSeconds();
        return new BunchingEpisode(
                InsightIds.bunching(routeId, pending.leader(), pending.follower(), start),
                routeId,
                evaluation.directionId(),
                pending.leader(),
                pending.follower(),
                evaluation.leaderTrip(),
                evaluation.followerTrip(),
                start,
                null,
                null,
                evaluation.headwaySeconds(),
                threshold,
                pendingMin == null ? evaluation.gapSeconds() : Math.min(pendingMin, evaluation.gapSeconds()),
                evaluation.gapSeconds(),
                pending.pendingStopId(),
                pending.consecutiveBelow(),
                tick);
    }

    public boolean isOpen() {
        return closeReason == null;
    }

    /** The open episode after one more evaluation of its pair. */
    BunchingEpisode evaluated(int gapSeconds, Instant tick) {
        return new BunchingEpisode(
                id,
                routeId,
                directionId,
                leader,
                follower,
                leaderTrip,
                followerTrip,
                episodeStart,
                null,
                null,
                scheduledHeadwaySeconds,
                thresholdSeconds,
                Math.min(minGapSeconds, gapSeconds),
                gapSeconds,
                openStopId,
                evaluationCount + 1,
                tick);
    }

    /** The episode closed at {@code end}. */
    BunchingEpisode closed(Instant end, CloseReason reason) {
        return new BunchingEpisode(
                id,
                routeId,
                directionId,
                leader,
                follower,
                leaderTrip,
                followerTrip,
                episodeStart,
                end,
                reason,
                scheduledHeadwaySeconds,
                thresholdSeconds,
                minGapSeconds,
                lastGapSeconds,
                openStopId,
                evaluationCount,
                lastEvaluatedAt);
    }
}
