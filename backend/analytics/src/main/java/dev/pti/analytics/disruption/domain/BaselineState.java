package dev.pti.analytics.disruption.domain;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The state of one (route, direction): the EWMA baseline and the counters of the hysteresis, as stored in
 * {@code analytics_route_baseline} and, once an hour, in {@code analytics_baseline_snapshot} (DOC-23 §6.2). A value
 * object: the state machine returns the next state instead of changing this one.
 *
 * @param mean μ, the baseline mean delay in seconds
 * @param variance the exponentially weighted variance
 * @param bucketCount how many buckets updated the baseline; the warm-up counter
 * @param lastBucket the end of the last bucket that was processed
 * @param consecutiveHigh buckets in a row that were above the open z-score while no episode was open
 * @param consecutiveLow buckets in a row that counted toward closing the open episode
 * @param openEpisodeId the episode that is open now, if any
 */
public record BaselineState(
        double mean,
        double variance,
        int bucketCount,
        Instant lastBucket,
        int consecutiveHigh,
        int consecutiveLow,
        @Nullable UUID openEpisodeId) {

    /** A direction that the detector has not seen: no baseline yet, nothing open. */
    public static BaselineState initial(Instant lastBucket) {
        return new BaselineState(0, 0, 0, lastBucket, 0, 0, null);
    }

    /** The standard deviation of the baseline. */
    public double sigma() {
        return Math.sqrt(variance);
    }
}
