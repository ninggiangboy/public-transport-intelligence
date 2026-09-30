package dev.pti.analytics.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.pti.analytics.TripUpdateRows;
import dev.pti.analytics.WarehouseSupport;
import dev.pti.analytics.config.AnalyticsPropertiesFixtures;
import dev.pti.analytics.core.adapter.out.jdbc.JdbcAdvisoryLock;
import dev.pti.analytics.core.adapter.out.jdbc.JdbcTransactionLimits;
import dev.pti.analytics.core.adapter.out.metrics.MicrometerAnalyticsMetrics;
import dev.pti.analytics.core.application.RunReporter;
import dev.pti.analytics.core.domain.LockNames;
import dev.pti.analytics.core.domain.Outcome;
import dev.pti.analytics.core.domain.RunResult;
import dev.pti.analytics.core.domain.Trigger;
import dev.pti.analytics.eta.adapter.out.jdbc.JdbcEtaAggregateStore;
import dev.pti.analytics.eta.application.EtaAggregator;
import dev.pti.analytics.eta.application.EtaPlan;
import dev.pti.analytics.eta.application.EtaPlanRequest;
import dev.pti.analytics.eta.application.EtaRouteRun;
import dev.pti.analytics.eta.application.EtaRunPlanner;
import dev.pti.analytics.otp.adapter.out.jdbc.JdbcOtpScorecardStore;
import dev.pti.analytics.otp.application.OtpDayRun;
import dev.pti.analytics.otp.application.OtpPlan;
import dev.pti.analytics.otp.application.OtpPlanRequest;
import dev.pti.analytics.otp.application.OtpRunPlanner;
import dev.pti.analytics.otp.application.OtpScorecardCalculator;
import dev.pti.analytics.reference.application.port.AnalyticsReferenceCache;
import dev.pti.analytics.reference.domain.RouteInfo;
import dev.pti.analytics.reference.domain.TripPattern;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.time.DayType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The ETA and OTP use cases with the real adapters: the lock, the statement timeout, the transaction and the metrics
 * around the SQL that the store ITs check (DOC-23 §2.5, §7.2, §12.1). The schedule data is a stand-in: the use cases
 * ask only whether a feed is ACTIVE and for its time zone.
 */
class EtaAndOtpUseCasesIT {

    private static final Instant HOUR = Instant.parse("2026-10-06T12:00:00Z");
    private static final LocalDate TUESDAY = LocalDate.parse("2026-09-29");
    private static final LocalDate BEFORE_HOUR = LocalDate.parse("2026-10-05");

    private final WarehouseSupport db = new WarehouseSupport();
    private final JdbcTemplate jdbc = new JdbcTemplate(db.dataSource);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final String route = "AN56-" + UUID.randomUUID().toString().substring(0, 8);
    private final TripUpdateRows facts = new TripUpdateRows(jdbc, route);
    private final LocalDate otpDay = LocalDate.parse("2035-05-05");

    private final AnalyticsReferenceCache reference = new AnalyticsReferenceCache() {
        @Override
        public boolean hasActiveFeed() {
            return true;
        }

        @Override
        public long feedVersionId() {
            return 1;
        }

        @Override
        public ZoneId agencyZone() {
            return ZoneId.of("America/Chicago");
        }

        @Override
        public Optional<RouteInfo> route(String routeId) {
            return Optional.empty();
        }

        @Override
        public Optional<TripPattern> trip(String tripId) {
            return Optional.empty();
        }

        @Override
        public OptionalInt scheduledHeadway(String routeId, int directionId, DayType dayType, int hour) {
            return OptionalInt.empty();
        }

        @Override
        public DayType dayType(LocalDate serviceDate) {
            return DayType.of(serviceDate);
        }
    };

    private final BusinessClock clock =
            new BusinessClock(Clock.fixed(Instant.parse("2035-05-06T15:00:00Z"), ZoneOffset.UTC), Duration.ZERO);
    private final RunReporter reporter = new RunReporter(new MicrometerAnalyticsMetrics(meters));
    private final JdbcEtaAggregateStore etaStore = new JdbcEtaAggregateStore(db.jdbc);
    private final EtaAggregator eta = new EtaAggregator(
            etaStore,
            reference,
            new JdbcAdvisoryLock(db.jdbc),
            new JdbcTransactionLimits(db.jdbc),
            db.tx,
            reporter,
            AnalyticsPropertiesFixtures.defaults().eta().toSettings());
    private final OtpScorecardCalculator otp = new OtpScorecardCalculator(
            new JdbcOtpScorecardStore(db.jdbc),
            reference,
            new JdbcAdvisoryLock(db.jdbc),
            new JdbcTransactionLimits(db.jdbc),
            db.tx,
            reporter,
            clock,
            AnalyticsPropertiesFixtures.defaults().otp().toSettings());

    @AfterEach
    void cleanUp() {
        facts.clear();
        jdbc.update("DELETE FROM insight.insight_eta_prediction WHERE route_id = ?", route);
        jdbc.update("DELETE FROM insight.insight_otp_scorecard WHERE route_id = ?", route);
        jdbc.update("DELETE FROM ops.etl_checkpoint WHERE checkpoint_key = 'analytics.eta'");
    }

    private double runs(String detector, String outcome) {
        var counter = meters.find("pti.analytics.runs")
                .tags("detector", detector, "trigger", "job", "outcome", outcome)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    /** True while another transaction could take the lock: nothing holds it. */
    private boolean lockIsFree(String name) {
        return db.tx.inNewTransaction(() -> Boolean.TRUE.equals(db.jdbc
                .sql("SELECT pg_try_advisory_xact_lock(hashtextextended(:name, 0))")
                .param("name", name)
                .query(Boolean.class)
                .single()));
    }

    @Test
    void theLastRouteStoresTheCheckpointWithItsMergeAndTheLockIsReleasedAtCommit() {
        facts.arrival(TUESDAY, Instant.parse("2026-09-29T22:10:00Z"), 60).insert();
        facts.arrival(TUESDAY, Instant.parse("2026-09-29T22:20:00Z"), 120).insert();
        UUID batch = UUID.randomUUID();
        // The route has a stale row, so the route list of a plan would contain it.
        jdbc.update("""
                INSERT INTO insight.insight_eta_prediction (route_id, stop_id, day_of_week, hour_of_day,
                  avg_delay_seconds, median_delay_seconds, p90_delay_seconds, sample_count, window_start, window_end,
                  computed_at, batch_id)
                VALUES (?, 'OLD', 1, 1, 1.0, 1, 1, 1, DATE '2026-09-01', DATE '2026-09-29',
                        TIMESTAMPTZ '2026-09-29 12:00:00Z', ?)""", route, UUID.randomUUID());

        RunResult result = eta.aggregate(new EtaRouteRun(
                route, HOUR, batch, Trigger.JOB, new EtaRouteRun.Completion("7|2026-10-06 11:00:00", 99L)));

        assertThat(result.outcome()).isEqualTo(Outcome.OK);
        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.deleted()).isEqualTo(1);
        assertThat(etaStore.checkpoint()).contains("7|2026-10-06 11:00:00");
        assertThat(jdbc.queryForObject(
                        "SELECT job_execution_id FROM ops.etl_checkpoint WHERE checkpoint_key = 'analytics.eta'",
                        Long.class))
                .isEqualTo(99L);
        assertThat(lockIsFree(LockNames.eta())).isTrue();
        assertThat(runs("eta", "ok")).isEqualTo(1);
    }

    @Test
    void thePlannerReadsTheRealWatermarkAndTheCheckpointThatAnAggregationStored() {
        EtaRunPlanner planner = new EtaRunPlanner(etaStore, reference, clock);
        facts.arrival(BEFORE_HOUR, Instant.parse("2026-10-05T22:10:00Z"), 60).insert();

        EtaPlan first = planner.plan(new EtaPlanRequest(HOUR, false));
        assertThat(first.status()).isEqualTo(EtaPlan.Status.RUN);

        eta.aggregate(new EtaRouteRun(
                route, HOUR, UUID.randomUUID(), Trigger.JOB, new EtaRouteRun.Completion(first.watermark(), null)));

        assertThat(planner.plan(new EtaPlanRequest(HOUR, false)).status())
                .as("same data, same watermark: nothing to do")
                .isEqualTo(EtaPlan.Status.UP_TO_DATE);
        assertThat(planner.plan(new EtaPlanRequest(HOUR, true)).status()).isEqualTo(EtaPlan.Status.RUN);

        facts.arrival(BEFORE_HOUR, Instant.parse("2026-10-05T22:20:00Z"), 60).insert();
        assertThat(planner.plan(new EtaPlanRequest(HOUR, false)).status())
                .as("a new observed arrival changes the watermark")
                .isEqualTo(EtaPlan.Status.RUN);
    }

    @Test
    void aDayIsScoredUnderItsLockAndCounted() {
        facts.arrival(otpDay, Instant.parse("2035-05-05T15:00:00Z"), 0).insert();
        facts.arrival(otpDay, Instant.parse("2035-05-05T15:10:00Z"), 600).insert();

        RunResult result = otp.calculate(new OtpDayRun(otpDay, UUID.randomUUID(), Trigger.JOB));

        assertThat(result.outcome()).isEqualTo(Outcome.OK);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM insight.insight_otp_scorecard WHERE route_id = ? AND service_date = ?", route, otpDay);
        assertThat(row.get("observation_count")).isEqualTo(2);
        assertThat(row.get("on_time_count")).isEqualTo(1);
        assertThat(lockIsFree(LockNames.otp(otpDay))).isTrue();
        assertThat(runs("otp", "ok")).isEqualTo(1);
    }

    @Test
    void theOtpPlannerUsesTheBusinessClockOfTheHost() {
        OtpRunPlanner planner = new OtpRunPlanner(
                reference, clock, AnalyticsPropertiesFixtures.defaults().otp().toSettings());

        OtpPlan plan = planner.plan(new OtpPlanRequest(null, null));

        // 15:00Z on 6 May 2035 is 10:00 CDT the same day.
        assertThat(plan.serviceDates())
                .containsExactly(
                        LocalDate.parse("2035-05-05"), LocalDate.parse("2035-05-04"), LocalDate.parse("2035-05-03"));
    }

    @Test
    void aDayThatIsNotOverIsRefusedAndCountedAsAnError() {
        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        otp.calculate(new OtpDayRun(LocalDate.parse("2035-05-06"), UUID.randomUUID(), Trigger.JOB)));

        assertThat(runs("otp", "error")).isEqualTo(1);
    }
}
