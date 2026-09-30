package dev.pti.analytics.otp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.analytics.config.AnalyticsPropertiesFixtures;
import dev.pti.analytics.core.application.RunReporter;
import dev.pti.analytics.core.domain.Outcome;
import dev.pti.analytics.core.domain.RunResult;
import dev.pti.analytics.core.domain.Trigger;
import dev.pti.analytics.otp.application.OtpPlan.Status;
import dev.pti.analytics.otp.application.port.OtpScorecardStore;
import dev.pti.analytics.otp.domain.OtpSettings;
import dev.pti.analytics.otp.domain.OtpTolerances;
import dev.pti.analytics.support.AnalyticsFakes;
import dev.pti.analytics.support.AnalyticsFakes.Journal;
import dev.pti.analytics.support.AnalyticsFakes.Limits;
import dev.pti.analytics.support.AnalyticsFakes.Lock;
import dev.pti.analytics.support.AnalyticsFakes.Metrics;
import dev.pti.analytics.support.AnalyticsFakes.Reference;
import dev.pti.analytics.support.AnalyticsFakes.Transactions;
import dev.pti.common.time.BusinessClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The planner and the per-day calculator of DOC-23 §8.2 with in-memory ports (AN-O-05, AN-O-06). */
class OtpAggregationTest {

    // 04:00 UTC on the 30th is still the evening of the 29th in Chicago; 12:00 UTC is the 30th.
    private final BusinessClock clock = AnalyticsFakes.clockAt("2026-09-30T12:00:00Z");
    private final OtpSettings settings =
            AnalyticsPropertiesFixtures.defaults().otp().toSettings();
    private final Journal journal = new Journal();
    private final Reference reference = new Reference();
    private final Lock lock = new Lock(journal);
    private final Limits limits = new Limits(journal);
    private final Transactions tx = new Transactions();
    private final Metrics metrics = new Metrics();
    private final Store store = new Store();
    private final OtpRunPlanner planner = new OtpRunPlanner(reference, clock, settings);
    private final OtpScorecardCalculator calculator =
            new OtpScorecardCalculator(store, reference, lock, limits, tx, new RunReporter(metrics), clock, settings);

    private final class Store implements OtpScorecardStore {

        final List<LocalDate> days = new ArrayList<>();
        final List<OtpTolerances> tolerances = new ArrayList<>();
        final List<Instant> computedAt = new ArrayList<>();

        @Override
        public Merge recompute(LocalDate serviceDate, OtpTolerances band, Instant at, UUID batchId) {
            journal.add("recompute " + serviceDate);
            days.add(serviceDate);
            tolerances.add(band);
            computedAt.add(at);
            return new Merge(4, 2);
        }
    }

    @Test
    void anO05TheNightlyRunWithRunDateThe30thScoresThreeDays() {
        OtpPlan plan = planner.plan(new OtpPlanRequest(null, LocalDate.parse("2026-09-30")));

        assertThat(plan.status()).isEqualTo(Status.RUN);
        assertThat(plan.serviceDates())
                .containsExactly(
                        LocalDate.parse("2026-09-29"), LocalDate.parse("2026-09-28"), LocalDate.parse("2026-09-27"));
    }

    @Test
    void aRunWithoutARunDateUsesTodayInChicago() {
        OtpPlan plan = planner.plan(new OtpPlanRequest(List.of(), null));

        assertThat(plan.serviceDates().getFirst()).isEqualTo(LocalDate.parse("2026-09-29"));
        assertThat(plan.serviceDates()).hasSize(3);
    }

    @Test
    void todayIsTheLocalDateNotTheUtcDate() {
        OtpRunPlanner night = new OtpRunPlanner(reference, AnalyticsFakes.clockAt("2026-09-30T03:00:00Z"), settings);

        // 03:00Z is 22:00 CDT on the 29th, so yesterday is the 28th.
        assertThat(night.plan(new OtpPlanRequest(null, null)).serviceDates().getFirst())
                .isEqualTo(LocalDate.parse("2026-09-28"));
    }

    @Test
    void requestedDatesReplaceTheDefault() {
        OtpPlan plan = planner.plan(new OtpPlanRequest(
                List.of(LocalDate.parse("2026-09-20"), LocalDate.parse("2026-09-22")), LocalDate.parse("2026-09-30")));

        assertThat(plan.serviceDates()).containsExactly(LocalDate.parse("2026-09-22"), LocalDate.parse("2026-09-20"));
    }

    @Test
    void anO06AFutureRequestedDateIsRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> planner.plan(new OtpPlanRequest(List.of(LocalDate.parse("2026-10-02")), null)))
                .withMessage("Service date 2026-10-02 is not before today (2026-09-30)");
    }

    @Test
    void withoutAnActiveFeedThePlanIsEmpty() {
        reference.active = false;

        assertThat(planner.plan(new OtpPlanRequest(null, null)).status()).isEqualTo(Status.NO_FEED);
    }

    @Test
    void aDayTakesItsOwnLockAfterTheStatementTimeoutAndBeforeTheMerge() {
        UUID batch = UUID.randomUUID();

        RunResult result = calculator.calculate(new OtpDayRun(LocalDate.parse("2026-09-29"), batch, Trigger.JOB));

        assertThat(journal.entries)
                .containsExactly("statement_timeout 60s", "lock pti:analytics:otp:2026-09-29", "recompute 2026-09-29");
        assertThat(lock.lockTimeout).isEqualTo(Duration.ofSeconds(60));
        assertThat(result.outcome()).isEqualTo(Outcome.OK);
        assertThat(result.updated()).as("rows upserted").isEqualTo(4);
        assertThat(result.deleted()).isEqualTo(2);
        assertThat(result.scope()).isEqualTo("2026-09-29");
        assertThat(store.computedAt).as("computed_at is the business time now").containsExactly(clock.instant());
        assertThat(store.tolerances).containsExactly(new OtpTolerances(300, 300));
        assertThat(metrics.runs).containsExactly("otp/job/ok");
    }

    @Test
    void theToleranceOfTheSettingsIsWhatTheStoreScoresWith() {
        OtpSettings tight = new OtpSettings(Duration.ofSeconds(60), Duration.ofSeconds(300), 2);
        OtpScorecardCalculator strict =
                new OtpScorecardCalculator(store, reference, lock, limits, tx, new RunReporter(metrics), clock, tight);

        strict.calculate(new OtpDayRun(LocalDate.parse("2026-09-29"), UUID.randomUUID(), Trigger.RECOMPUTE));

        assertThat(store.tolerances).containsExactly(new OtpTolerances(60, 300));
        assertThat(metrics.runs).containsExactly("otp/recompute/ok");
    }

    @Test
    void aFutureDayIsRefusedBeforeAnythingIsLocked() {
        assertThatThrownBy(() -> calculator.calculate(
                        new OtpDayRun(LocalDate.parse("2026-09-30"), UUID.randomUUID(), Trigger.JOB)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not before today");

        assertThat(journal.entries).isEmpty();
        assertThat(metrics.runs).containsExactly("otp/job/error");
    }

    @Test
    void withoutAnActiveFeedADayIsANoop() {
        reference.active = false;

        RunResult result =
                calculator.calculate(new OtpDayRun(LocalDate.parse("2026-09-29"), UUID.randomUUID(), Trigger.JOB));

        assertThat(result.outcome()).isEqualTo(Outcome.NOOP);
        assertThat(journal.entries).isEmpty();
        assertThat(metrics.runs).containsExactly("otp/job/noop");
    }
}
