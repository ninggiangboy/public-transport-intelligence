package dev.pti.analytics.disruption.domain;

import java.time.Duration;
import java.util.Set;

/**
 * The tunable values of disruption detection (DOC-23 §13, {@code pti.analytics.disruption.*}).
 *
 * @param routeTypes the GTFS {@code route_type}s that are evaluated
 * @param bucket the bucket length, fixed at one minute because the baseline snapshot table depends on it
 * @param window the sliding window of the current value
 * @param minSamples fewer arrivals than this in the window make a no-data bucket
 * @param alpha the EWMA weight of a new bucket
 * @param sigmaFloor the smallest standard deviation used for a z-score
 * @param openZ a z-score above this counts toward opening an episode
 * @param openConsecutive how many buckets in a row must be above the open z-score
 * @param closeZ a z-score below this counts toward closing
 * @param closeConsecutive how many buckets in a row must be below the close z-score
 * @param warmUpBuckets buckets the baseline needs before an episode may open
 * @param maxEpisodeDuration an episode that lasts this long is closed and the baseline re-seeded
 * @param severityHighZ the peak z-score from which the alert has severity 2
 * @param allowedLateness the watermark stays this far behind the newest arrival
 * @param idleTimeout the watermark follows the clock this far behind when a route sends nothing
 * @param maxCatchUp the most a route may fall behind before its cursor jumps ahead
 */
public record DisruptionThresholds(
        Set<Integer> routeTypes,
        Duration bucket,
        Duration window,
        int minSamples,
        double alpha,
        Duration sigmaFloor,
        double openZ,
        int openConsecutive,
        double closeZ,
        int closeConsecutive,
        int warmUpBuckets,
        Duration maxEpisodeDuration,
        double severityHighZ,
        Duration allowedLateness,
        Duration idleTimeout,
        Duration maxCatchUp) {

    public DisruptionThresholds {
        routeTypes = Set.copyOf(routeTypes);
    }
}
