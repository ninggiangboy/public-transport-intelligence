package dev.pti.analytics.bunching.domain;

/**
 * The result for one follower at one grid point (DOC-23 §5.4).
 *
 * @param stopId the follower's next stop, where the gap is measured
 * @param gapSeconds when the follower is expected at the stop minus when the leader passed it, rounded to seconds
 * @param headwaySeconds the scheduled headway of the hour at the grid point
 * @param source how the leader's passage was found
 */
public record Evaluation(
        String leader,
        String follower,
        String leaderTrip,
        String followerTrip,
        int directionId,
        String stopId,
        int gapSeconds,
        int headwaySeconds,
        PassSource source) {

    public PairKey key() {
        return new PairKey(leader, follower);
    }
}
