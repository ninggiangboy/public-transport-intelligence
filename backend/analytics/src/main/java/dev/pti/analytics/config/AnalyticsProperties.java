package dev.pti.analytics.config;

import dev.pti.analytics.bunching.domain.BunchingThresholds;
import dev.pti.analytics.disruption.domain.DisruptionThresholds;
import dev.pti.analytics.eta.domain.EtaSettings;
import dev.pti.analytics.otp.domain.OtpSettings;
import dev.pti.analytics.ticketing.domain.TicketingThresholds;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code pti.analytics.*} (DOC-23 §13, DOC-29 §3.4). The defaults are in the {@code application.yml} of the app that
 * runs analytics. A value that breaks a rule, for example {@code close-ratio ≤ open-ratio}, stops the app at startup.
 * The {@code to*()} methods hand the values to the detectors as plain records, so that {@code domain} and
 * {@code application} never see Spring (DOC-49 §4.1).
 */
@ConfigurationProperties("pti.analytics")
@Validated
public record AnalyticsProperties(
        boolean enabled,
        @NotNull @Valid Dispatcher dispatcher,
        @NotNull @Valid Bunching bunching,
        @NotNull @Valid Disruption disruption,
        @NotNull @Valid Eta eta,
        @NotNull @Valid Otp otp,
        @NotNull @Valid Ticketing ticketing) {

    /** The tick of DOC-23 §4.2. */
    public record Dispatcher(@NotNull Duration tickInterval) {

        @AssertTrue(message = "tick-interval must be positive")
        boolean isTickIntervalPositive() {
            return Checks.positive(tickInterval);
        }
    }

    public record Bunching(
            boolean enabled,
            @NotEmpty List<Integer> routeTypes,
            @NotNull Duration evaluationInterval,
            @NotNull Duration allowedLateness,
            @NotNull Duration idleTimeout,
            @NotNull Duration positionMaxAge,

            @DecimalMin(value = "0", inclusive = false) @DecimalMax("1")
            double openRatio,

            @DecimalMin(value = "0", inclusive = false) double closeRatio,
            @Min(1) int openConsecutive,
            @Min(0) int excludeFirstStops,
            @Min(0) int excludeLastStops,
            @NotNull Duration maxHeadway,
            @NotNull Duration leaderLookback,
            @NotNull Duration maxCatchUp) {

        @AssertTrue(message = "close-ratio must be greater than open-ratio")
        boolean isCloseRatioAboveOpenRatio() {
            return closeRatio > openRatio;
        }

        @AssertTrue(message = "evaluation-interval must be a whole number of seconds that divides 60 seconds")
        boolean isEvaluationIntervalAGridStep() {
            return evaluationInterval == null || Checks.dividesMinute(evaluationInterval);
        }

        @AssertTrue(
                message = "idle-timeout, position-max-age, max-headway and leader-lookback must be positive"
                        + " and allowed-lateness must not be negative")
        boolean isTimingPlausible() {
            return Checks.positive(idleTimeout)
                    && Checks.positive(positionMaxAge)
                    && Checks.positive(maxHeadway)
                    && Checks.positive(leaderLookback)
                    && Checks.notNegative(allowedLateness);
        }

        @AssertTrue(message = "max-catch-up must be at least one evaluation-interval")
        boolean isCatchUpAtLeastOneStep() {
            return maxCatchUp == null || evaluationInterval == null || maxCatchUp.compareTo(evaluationInterval) >= 0;
        }

        public BunchingThresholds toThresholds() {
            return new BunchingThresholds(
                    Set.copyOf(routeTypes),
                    evaluationInterval,
                    allowedLateness,
                    idleTimeout,
                    positionMaxAge,
                    openRatio,
                    closeRatio,
                    openConsecutive,
                    excludeFirstStops,
                    excludeLastStops,
                    maxHeadway,
                    leaderLookback,
                    maxCatchUp);
        }
    }

    public record Disruption(
            boolean enabled,
            @NotEmpty List<Integer> routeTypes,
            @NotNull Duration bucket,
            @NotNull Duration window,
            @Min(1) int minSamples,

            @DecimalMin(value = "0", inclusive = false) @DecimalMax("1")
            double alpha,

            @NotNull Duration sigmaFloor,
            @DecimalMin(value = "0", inclusive = false) double openZ,
            @Min(1) int openConsecutive,
            @DecimalMin(value = "0", inclusive = false) double closeZ,
            @Min(1) int closeConsecutive,
            @Min(1) int warmUpBuckets,
            @NotNull Duration maxEpisodeDuration,
            @DecimalMin(value = "0", inclusive = false) double severityHighZ,
            @NotNull Duration allowedLateness,
            @NotNull Duration idleTimeout,
            @NotNull Duration maxCatchUp) {

        @AssertTrue(message = "close-z must be less than open-z")
        boolean isCloseZBelowOpenZ() {
            return closeZ < openZ;
        }

        @AssertTrue(message = "severity-high-z must be at least open-z")
        boolean isSeverityHighZAtLeastOpenZ() {
            return severityHighZ >= openZ;
        }

        @AssertTrue(message = "bucket is fixed at 1m: the baseline snapshot table depends on it")
        boolean isBucketOneMinute() {
            return bucket == null || bucket.equals(Duration.ofMinutes(1));
        }

        @AssertTrue(message = "window must be a positive multiple of bucket")
        boolean isWindowAMultipleOfBucket() {
            return window == null
                    || bucket == null
                    || !Checks.positive(window)
                    || window.toNanos() % bucket.toNanos() == 0;
        }

        @AssertTrue(
                message = "sigma-floor, max-episode-duration, idle-timeout and window must be positive"
                        + " and allowed-lateness must not be negative")
        boolean isTimingPlausible() {
            return Checks.positive(sigmaFloor)
                    && Checks.positive(maxEpisodeDuration)
                    && Checks.positive(idleTimeout)
                    && Checks.positive(window)
                    && Checks.notNegative(allowedLateness);
        }

        @AssertTrue(message = "max-catch-up must be at least one bucket")
        boolean isCatchUpAtLeastOneBucket() {
            return maxCatchUp == null || bucket == null || maxCatchUp.compareTo(bucket) >= 0;
        }

        public DisruptionThresholds toThresholds() {
            return new DisruptionThresholds(
                    Set.copyOf(routeTypes),
                    bucket,
                    window,
                    minSamples,
                    alpha,
                    sigmaFloor,
                    openZ,
                    openConsecutive,
                    closeZ,
                    closeConsecutive,
                    warmUpBuckets,
                    maxEpisodeDuration,
                    severityHighZ,
                    allowedLateness,
                    idleTimeout,
                    maxCatchUp);
        }
    }

    public record Eta(
            @NotNull Duration window,
            @NotNull @Valid Confidence confidence,
            boolean realtimeEnabled,
            @NotNull Duration realtimeMaxAge,
            @NotNull @Valid Arrivals arrivals) {

        /** The sample counts from which ETA confidence is medium and high (§7.3). */
        public record Confidence(
                @Min(1) int mediumMin, @Min(1) int highMin) {

            @AssertTrue(message = "medium-min must be less than high-min")
            boolean isMediumBelowHigh() {
                return mediumMin < highMin;
            }
        }

        /** Read by {@code api} (§7.4). */
        public record Arrivals(
                @Min(1) int defaultLimit, @NotNull Duration horizon) {

            @AssertTrue(message = "horizon must be positive")
            boolean isHorizonPositive() {
                return Checks.positive(horizon);
            }
        }

        @AssertTrue(message = "window and realtime-max-age must be positive")
        boolean isTimingPositive() {
            return Checks.positive(window) && Checks.positive(realtimeMaxAge);
        }

        public EtaSettings toSettings() {
            return new EtaSettings(
                    window,
                    confidence.mediumMin(),
                    confidence.highMin(),
                    realtimeEnabled,
                    realtimeMaxAge,
                    arrivals.defaultLimit(),
                    arrivals.horizon());
        }
    }

    public record Otp(
            @NotNull Duration earlyTolerance,
            @NotNull Duration lateTolerance,
            @Min(1) int recomputeDays) {

        @AssertTrue(message = "early-tolerance and late-tolerance must not be negative")
        boolean isToleranceNotNegative() {
            return Checks.notNegative(earlyTolerance) && Checks.notNegative(lateTolerance);
        }

        public OtpSettings toSettings() {
            return new OtpSettings(earlyTolerance, lateTolerance, recomputeDays);
        }
    }

    public record Ticketing(
            @NotNull Duration window,
            @NotNull Duration allowedLateness,
            @Min(1) int baselineWeeks,
            @Min(1) int minBaselineWindows,
            @Min(1) int coldStartWindows,
            @DecimalMin(value = "0", inclusive = false) double volumeZ,
            @Min(1) int volumeMinTxn,

            @DecimalMin(value = "0", inclusive = false) @DecimalMax("1")
            double refundRatio,

            @Min(1) int refundMinCount,
            @NotNull Duration maxCatchUp) {

        @AssertTrue(message = "window is fixed at 15m: it is part of the key of an anomaly")
        boolean isWindowFifteenMinutes() {
            return window == null || window.equals(Duration.ofMinutes(15));
        }

        @AssertTrue(message = "allowed-lateness must not be negative")
        boolean isAllowedLatenessNotNegative() {
            return Checks.notNegative(allowedLateness);
        }

        @AssertTrue(message = "max-catch-up must be at least one window")
        boolean isCatchUpAtLeastOneWindow() {
            return maxCatchUp == null || window == null || maxCatchUp.compareTo(window) >= 0;
        }

        public TicketingThresholds toThresholds() {
            return new TicketingThresholds(
                    window,
                    allowedLateness,
                    baselineWeeks,
                    minBaselineWindows,
                    coldStartWindows,
                    volumeZ,
                    volumeMinTxn,
                    refundRatio,
                    refundMinCount,
                    maxCatchUp);
        }
    }

    /** Null-tolerant checks for the {@code @AssertTrue} methods: a missing value is reported by {@code @NotNull}. */
    private static final class Checks {

        private Checks() {}

        static boolean positive(Duration value) {
            return value == null || (!value.isNegative() && !value.isZero());
        }

        static boolean notNegative(Duration value) {
            return value == null || !value.isNegative();
        }

        static boolean dividesMinute(Duration value) {
            return value.toNanosPart() == 0 && value.getSeconds() > 0 && 60 % value.getSeconds() == 0;
        }
    }
}
