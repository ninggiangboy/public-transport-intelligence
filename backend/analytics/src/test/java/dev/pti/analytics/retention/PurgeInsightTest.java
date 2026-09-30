package dev.pti.analytics.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.pti.analytics.retention.application.PurgeInsight;
import dev.pti.analytics.retention.application.PurgeInsightRequest;
import dev.pti.analytics.retention.application.port.InsightRetentionStore;
import dev.pti.analytics.retention.application.port.RetentionMetrics;
import dev.pti.analytics.retention.domain.RetentionCutoff;
import dev.pti.analytics.retention.domain.RetentionPolicy;
import dev.pti.analytics.retention.domain.RetentionTarget;
import dev.pti.analytics.support.AnalyticsFakes.Transactions;
import dev.pti.common.time.BusinessClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The cutoffs of DOC-23 §12.3 and the use case that applies them one batch at a time. */
class PurgeInsightTest {

    private static final Instant REAL_NOW = Instant.parse("2026-09-30T12:00:00Z");
    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

    private final RetentionPolicy policy = new RetentionPolicy(Duration.ofDays(365), Duration.ofDays(30), CHICAGO);

    @Test
    void episodesAnomaliesAndSnapshotsExpireByTheirEventTimeOnTheBusinessClock() {
        Instant businessNow = Instant.parse("2026-09-30T18:00:00Z");

        assertThat(policy.cutoff(RetentionTarget.BUNCHING, businessNow, REAL_NOW)
                        .instant())
                .isEqualTo(businessNow.minus(Duration.ofDays(365)));
        assertThat(policy.cutoff(RetentionTarget.DISRUPTION, businessNow, REAL_NOW)
                        .instant())
                .isEqualTo(businessNow.minus(Duration.ofDays(365)));
        assertThat(policy.cutoff(RetentionTarget.TICKETING_ANOMALY, businessNow, REAL_NOW)
                        .instant())
                .isEqualTo(businessNow.minus(Duration.ofDays(365)));
        assertThat(policy.cutoff(RetentionTarget.BASELINE_SNAPSHOT, businessNow, REAL_NOW)
                        .instant())
                .as("pti.retention.baseline-snapshot, not insight")
                .isEqualTo(businessNow.minus(Duration.ofDays(30)));
        assertThat(policy.cutoff(RetentionTarget.BUNCHING_CURSOR, businessNow, REAL_NOW)
                        .instant())
                .as("seven days, fixed")
                .isEqualTo(businessNow.minus(Duration.ofDays(7)));
    }

    @Test
    void aSuggestionExpiresByItsCreatedAtOnTheRealClock() {
        Instant businessNow = REAL_NOW.minus(Duration.ofHours(12));

        assertThat(policy.cutoff(RetentionTarget.DISPATCH_SUGGESTION, businessNow, REAL_NOW)
                        .instant())
                .isEqualTo(REAL_NOW.minus(Duration.ofDays(365)));
    }

    @Test
    void aScorecardExpiresByServiceDateAgainstTheLocalDateOfTheBusinessClock() {
        // 03:00Z on the 30th is still the 29th in Chicago.
        Instant businessNow = Instant.parse("2026-09-30T03:00:00Z");

        assertThat(policy.cutoff(RetentionTarget.OTP_SCORECARD, businessNow, REAL_NOW)
                        .serviceDate())
                .isEqualTo(LocalDate.parse("2026-09-29").minusDays(365));
    }

    @Test
    void aPolicyNeedsPositiveRetentions() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RetentionPolicy(Duration.ZERO, Duration.ofDays(30), CHICAGO));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RetentionPolicy(Duration.ofDays(365), Duration.ofDays(-1), CHICAGO));
    }

    @Test
    void theTargetsAreInTheOrderOfDoc23AndEveryTableIsInInsight() {
        assertThat(RetentionTarget.values())
                .extracting(RetentionTarget::table)
                .containsExactly(
                        "insight.insight_bus_bunching",
                        "insight.insight_service_disruption",
                        "insight.insight_ticketing_anomaly",
                        "insight.insight_otp_scorecard",
                        "insight.insight_dispatch_suggestion",
                        "insight.analytics_baseline_snapshot",
                        "insight.analytics_bunching_cursor");
    }

    @Test
    void oneCallDeletesOneBatchInTheCallersTransactionAndCountsIt() {
        List<String> calls = new ArrayList<>();
        List<String> counted = new ArrayList<>();
        InsightRetentionStore store = (target, cutoff, limit) -> {
            calls.add(target + "<" + cutoff.instant() + " limit " + limit);
            return 7;
        };
        RetentionMetrics metrics = (table, count) -> counted.add(table + "=" + count);
        Transactions tx = new Transactions();
        BusinessClock clock = new BusinessClock(Clock.fixed(REAL_NOW, ZoneOffset.UTC), Duration.ofHours(-3));
        PurgeInsight purge = new PurgeInsight(store, metrics, tx, clock, policy);

        int deleted = purge.execute(new PurgeInsightRequest(RetentionTarget.BUNCHING, 5000));

        assertThat(deleted).isEqualTo(7);
        assertThat(calls).containsExactly("BUNCHING<" + clock.instant().minus(Duration.ofDays(365)) + " limit 5000");
        assertThat(counted).containsExactly("insight.insight_bus_bunching=7");
        assertThat(tx.joined).isEqualTo(1);
    }

    @Test
    void aBatchSizeMustBePositive() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PurgeInsightRequest(RetentionTarget.BUNCHING, 0));
    }

    @Test
    void theCutoffOfAServiceDateIsTheOnlyOneThatUsesTheDate() {
        RetentionCutoff cutoff = policy.cutoff(RetentionTarget.OTP_SCORECARD, REAL_NOW, REAL_NOW);

        assertThat(cutoff.serviceDate()).isEqualTo(LocalDate.parse("2026-09-30").minusDays(365));
    }
}
