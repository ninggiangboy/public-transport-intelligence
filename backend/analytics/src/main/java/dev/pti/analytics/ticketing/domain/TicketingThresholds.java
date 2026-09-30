package dev.pti.analytics.ticketing.domain;

import java.time.Duration;

/**
 * The tunable values of ticketing anomaly detection (DOC-23 §13, {@code pti.analytics.ticketing.*}).
 *
 * @param window the window length, fixed at 15 minutes because it is part of the anomaly's key
 * @param allowedLateness how long after the end of a window the job waits for late sales
 * @param baselineWeeks how many weeks of the same weekday and hour form the baseline
 * @param minBaselineWindows the fewest windows a baseline may rest on
 * @param coldStartWindows the windows the recent-history fallback needs
 * @param volumeZ the z-score of the transaction count above which a window is unusual
 * @param volumeMinTxn the fewest transactions a window needs for the volume rule
 * @param refundRatio the refund ratio above which a window is unusual
 * @param refundMinCount the fewest refunds a window needs for the refund rule
 * @param maxCatchUp the most the job goes back when it has fallen behind
 */
public record TicketingThresholds(
        Duration window,
        Duration allowedLateness,
        int baselineWeeks,
        int minBaselineWindows,
        int coldStartWindows,
        double volumeZ,
        int volumeMinTxn,
        double refundRatio,
        int refundMinCount,
        Duration maxCatchUp) {}
