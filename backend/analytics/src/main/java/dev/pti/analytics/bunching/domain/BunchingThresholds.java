package dev.pti.analytics.bunching.domain;

import java.time.Duration;
import java.util.Set;

/**
 * The tunable values of bunching detection (DOC-23 §13, {@code pti.analytics.bunching.*}). Plain values: {@code
 * AnalyticsProperties} validates and converts them, the detector only reads them.
 *
 * @param routeTypes the GTFS {@code route_type}s that are evaluated (FR-05.2)
 * @param evaluationInterval the step of the evaluation grid; divides 60 seconds
 * @param allowedLateness the watermark stays this far behind the newest position (§2.2)
 * @param idleTimeout the watermark follows the clock this far behind when a route sends nothing (§2.2)
 * @param positionMaxAge a position older than this means the vehicle is not running
 * @param openRatio a gap below {@code openRatio × headway} counts toward opening an episode
 * @param closeRatio a gap above {@code closeRatio × headway} closes it
 * @param openConsecutive how many evaluations in a row must be below the open ratio
 * @param excludeFirstStops vehicles at one of this many first stops are not evaluated
 * @param excludeLastStops vehicles at one of this many last stops are not evaluated
 * @param maxHeadway a longer scheduled headway is not evaluated
 * @param leaderLookback how far back the leader's passage of a stop is searched
 * @param maxCatchUp the most a route may fall behind before its cursor jumps ahead (§2.2)
 */
public record BunchingThresholds(
        Set<Integer> routeTypes,
        Duration evaluationInterval,
        Duration allowedLateness,
        Duration idleTimeout,
        Duration positionMaxAge,
        double openRatio,
        double closeRatio,
        int openConsecutive,
        int excludeFirstStops,
        int excludeLastStops,
        Duration maxHeadway,
        Duration leaderLookback,
        Duration maxCatchUp) {

    public BunchingThresholds {
        routeTypes = Set.copyOf(routeTypes);
    }
}
