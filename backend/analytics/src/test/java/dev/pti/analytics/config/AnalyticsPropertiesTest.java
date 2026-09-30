package dev.pti.analytics.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.analytics.bunching.domain.BunchingThresholds;
import dev.pti.analytics.disruption.domain.DisruptionThresholds;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;

/** DOC-23 §13: the key table, its defaults and the rules that stop the app at startup (AN-I-08). */
class AnalyticsPropertiesTest {

    @Test
    void theDefaultsAreTheDocumentedValues() {
        AnalyticsProperties props = AnalyticsPropertiesFixtures.defaults();

        assertThat(props.enabled()).isTrue();
        assertThat(props.dispatcher().tickInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(props.bunching().toThresholds())
                .isEqualTo(new BunchingThresholds(
                        Set.of(3),
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(120),
                        0.5,
                        0.7,
                        2,
                        2,
                        2,
                        Duration.ofMinutes(30),
                        Duration.ofMinutes(30),
                        Duration.ofMinutes(15)));
        assertThat(props.disruption().toThresholds())
                .isEqualTo(new DisruptionThresholds(
                        Set.of(0, 3),
                        Duration.ofMinutes(1),
                        Duration.ofMinutes(10),
                        5,
                        0.1,
                        Duration.ofSeconds(30),
                        2.5,
                        2,
                        1.5,
                        3,
                        60,
                        Duration.ofHours(3),
                        4.0,
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(60),
                        Duration.ofMinutes(60)));
        assertThat(props.eta().toSettings().window()).isEqualTo(Duration.ofDays(28));
        assertThat(props.eta().toSettings().mediumMin()).isEqualTo(10);
        assertThat(props.eta().toSettings().highMin()).isEqualTo(30);
        assertThat(props.eta().toSettings().realtimeEnabled()).isFalse();
        assertThat(props.eta().toSettings().arrivalsHorizon()).isEqualTo(Duration.ofMinutes(90));
        assertThat(props.otp().toSettings().earlyTolerance()).isEqualTo(Duration.ofSeconds(300));
        assertThat(props.otp().toSettings().recomputeDays()).isEqualTo(2);
        assertThat(props.ticketing().toThresholds().window()).isEqualTo(Duration.ofMinutes(15));
        assertThat(props.ticketing().toThresholds().volumeZ()).isEqualTo(3.0);
        assertThat(props.ticketing().toThresholds().refundRatio()).isEqualTo(0.3);
        assertThat(props.ticketing().toThresholds().maxCatchUp()).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void aCloseRatioBelowTheOpenRatioStopsTheApp() {
        assertRejected(Map.of("bunching.close-ratio", "0.4"), "close-ratio must be greater than open-ratio");
        assertRejected(Map.of("bunching.close-ratio", "0.5"), "close-ratio must be greater than open-ratio");
    }

    @Test
    void aCloseZAtOrAboveTheOpenZStopsTheApp() {
        assertRejected(Map.of("disruption.close-z", "2.5"), "close-z must be less than open-z");
        assertRejected(Map.of("disruption.close-z", "3.0"), "close-z must be less than open-z");
    }

    @Test
    void theEvaluationIntervalMustDivideAMinute() {
        assertRejected(
                Map.of("bunching.evaluation-interval", "7s"),
                "evaluation-interval must be a whole number of seconds that divides 60 seconds");
        assertThat(AnalyticsPropertiesFixtures.bind(Map.of("bunching.evaluation-interval", "10s"))
                        .bunching()
                        .evaluationInterval())
                .isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void theFixedWindowsCannotBeChanged() {
        assertRejected(Map.of("disruption.bucket", "30s"), "bucket is fixed at 1m");
        assertRejected(Map.of("ticketing.window", "10m"), "window is fixed at 15m");
    }

    @Test
    void theDisruptionWindowIsAMultipleOfTheBucket() {
        assertRejected(Map.of("disruption.window", "90s"), "window must be a positive multiple of bucket");
    }

    @Test
    void theSeverityThresholdIsNotBelowTheOpenThreshold() {
        assertRejected(Map.of("disruption.severity-high-z", "2.0"), "severity-high-z must be at least open-z");
    }

    @Test
    void ratiosAndCountsAreRangeChecked() {
        assertRejected(Map.of("bunching.open-ratio", "0"), "openRatio");
        assertRejected(Map.of("bunching.open-consecutive", "0"), "openConsecutive");
        assertRejected(Map.of("disruption.alpha", "1.5"), "alpha");
        assertRejected(Map.of("ticketing.refund-ratio", "1.2"), "refundRatio");
        assertRejected(Map.of("otp.recompute-days", "0"), "recomputeDays");
    }

    @Test
    void durationsMustBePositiveWhereTimeMustPass() {
        assertRejected(Map.of("dispatcher.tick-interval", "0s"), "tick-interval must be positive");
        assertRejected(Map.of("bunching.idle-timeout", "0s"), "idle-timeout");
        assertRejected(Map.of("bunching.allowed-lateness", "-1s"), "allowed-lateness must not be negative");
        assertRejected(Map.of("eta.window", "0s"), "window and realtime-max-age must be positive");
    }

    @Test
    void theEtaConfidenceLevelsAreOrdered() {
        assertRejected(Map.of("eta.confidence.medium-min", "30"), "medium-min must be less than high-min");
    }

    @Test
    void theCatchUpLimitCoversAtLeastOneStep() {
        assertRejected(Map.of("bunching.max-catch-up", "5s"), "max-catch-up must be at least one evaluation-interval");
        assertRejected(Map.of("disruption.max-catch-up", "30s"), "max-catch-up must be at least one bucket");
        assertRejected(Map.of("ticketing.max-catch-up", "10m"), "max-catch-up must be at least one window");
    }

    @Test
    void aMissingRouteTypeListIsRejected() {
        assertRejected(Map.of("bunching.route-types", ""), "routeTypes");
    }

    private static void assertRejected(Map<String, String> overrides, String reason) {
        assertThatThrownBy(() -> AnalyticsPropertiesFixtures.bind(overrides))
                .isInstanceOf(BindException.class)
                .hasStackTraceContaining(reason);
    }
}
