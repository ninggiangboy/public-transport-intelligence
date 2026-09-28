package dev.pti.simulator.motion;

import java.time.Duration;

/**
 * The delay model's tuning (DOC-25 §5.3, §5.4; DOC-29 §3.2). Per-period values are {@code [PEAK, OFF_PEAK]}, in
 * seconds.
 */
public record DelayParameters(
        PerPeriod initialMean,
        PerPeriod initialSd,
        PerPeriod drift,
        PerPeriod segmentSd,
        PerPeriod dwellMean,
        double earlyLimit,
        double lateLimit,
        double minSpeedRatio,
        double routeFactorPhi,
        double routeFactorSigma,
        Duration routeFactorBucket) {

    /** The documented defaults. */
    public static DelayParameters defaults() {
        return new DelayParameters(
                new PerPeriod(60, 20),
                new PerPeriod(45, 30),
                new PerPeriod(-8, -5),
                new PerPeriod(12, 8),
                new PerPeriod(8, 5),
                -120,
                1200,
                0.5,
                0.8,
                0.15,
                Duration.ofMinutes(5));
    }

    /** One value for peak and one for off-peak, in seconds. */
    public record PerPeriod(double peak, double offPeak) {

        public double of(Period period) {
            return period == Period.PEAK ? peak : offPeak;
        }
    }
}
