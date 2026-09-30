package dev.pti.analytics.bunching.domain;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A row of {@code insight.analytics_bunching_pair_state} (DOC-23 §5.5): what the detector remembers about a pair
 * between grid points. A pair is counting while {@code openEpisodeId} is {@code null} and has an open episode once it
 * is set.
 *
 * @param consecutiveBelow evaluations in a row below the open ratio
 * @param firstBelowAt event time of the first of them; becomes {@code episode_start}
 * @param pendingMinGapSeconds the smallest gap while counting; becomes {@code min_gap_seconds}
 * @param pendingStopId the follower's next stop at {@code firstBelowAt}; becomes {@code open_stop_id}
 * @param openEpisodeId the episode the pair has open
 * @param lastEvaluatedAt the last grid point at which the pair was evaluated
 */
public record PairState(
        int directionId,
        String leader,
        String follower,
        String leaderTrip,
        String followerTrip,
        int consecutiveBelow,
        @Nullable Instant firstBelowAt,
        @Nullable Integer pendingMinGapSeconds,
        @Nullable String pendingStopId,
        @Nullable UUID openEpisodeId,
        Instant lastEvaluatedAt) {

    public PairKey key() {
        return new PairKey(leader, follower);
    }

    public boolean isOpen() {
        return openEpisodeId != null;
    }

    /** True when the evaluation is about these same two trips; a new trip of either vehicle makes a new pair. */
    boolean sameTrips(Evaluation evaluation) {
        return leaderTrip.equals(evaluation.leaderTrip()) && followerTrip.equals(evaluation.followerTrip());
    }

    /** A pair that has just been seen below the open ratio for the first time, before it is counted. */
    static PairState beginning(Evaluation first, Instant tick) {
        return new PairState(
                first.directionId(),
                first.leader(),
                first.follower(),
                first.leaderTrip(),
                first.followerTrip(),
                0,
                tick,
                first.gapSeconds(),
                first.stopId(),
                null,
                tick);
    }

    /** One more evaluation below the open ratio. */
    PairState countedBelow(int gapSeconds, Instant tick) {
        return new PairState(
                directionId,
                leader,
                follower,
                leaderTrip,
                followerTrip,
                consecutiveBelow + 1,
                firstBelowAt,
                pendingMinGapSeconds == null ? gapSeconds : Math.min(pendingMinGapSeconds, gapSeconds),
                pendingStopId,
                openEpisodeId,
                tick);
    }

    /** The pair after its episode opened. */
    PairState withOpenEpisode(UUID episodeId) {
        return new PairState(
                directionId,
                leader,
                follower,
                leaderTrip,
                followerTrip,
                consecutiveBelow,
                firstBelowAt,
                pendingMinGapSeconds,
                pendingStopId,
                episodeId,
                lastEvaluatedAt);
    }

    /** The pair after one more evaluation while its episode stays open. */
    PairState evaluatedAt(Instant tick) {
        return new PairState(
                directionId,
                leader,
                follower,
                leaderTrip,
                followerTrip,
                consecutiveBelow,
                firstBelowAt,
                pendingMinGapSeconds,
                pendingStopId,
                openEpisodeId,
                tick);
    }
}
