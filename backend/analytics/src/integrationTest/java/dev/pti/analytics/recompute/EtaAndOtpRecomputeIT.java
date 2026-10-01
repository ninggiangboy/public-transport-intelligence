package dev.pti.analytics.recompute;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.analytics.TripUpdateRows;
import dev.pti.analytics.WarehouseSupport;
import dev.pti.analytics.config.AnalyticsPropertiesFixtures;
import dev.pti.analytics.core.adapter.out.jdbc.JdbcAdvisoryLock;
import dev.pti.analytics.core.adapter.out.jdbc.JdbcTransactionLimits;
import dev.pti.analytics.core.adapter.out.metrics.MicrometerAnalyticsMetrics;
import dev.pti.analytics.core.application.RunReporter;
import dev.pti.analytics.core.domain.Detector;
import dev.pti.analytics.core.domain.ServiceDates;
import dev.pti.analytics.eta.adapter.out.jdbc.JdbcEtaAggregateStore;
import dev.pti.analytics.eta.application.EtaAggregator;
import dev.pti.analytics.eta.application.EtaRunPlanner;
import dev.pti.analytics.otp.adapter.out.jdbc.JdbcOtpScorecardStore;
import dev.pti.analytics.otp.application.OtpScorecardCalculator;
import dev.pti.analytics.recompute.adapter.out.metrics.MicrometerRecomputeMetrics;
import dev.pti.analytics.recompute.application.DetectorRecomputeService;
import dev.pti.analytics.recompute.application.EtaRecompute;
import dev.pti.analytics.recompute.application.OtpRecompute;
import dev.pti.analytics.recompute.domain.DetectorStats;
import dev.pti.analytics.recompute.domain.WorkItem;
import dev.pti.analytics.reference.application.port.AnalyticsReferenceCache;
import dev.pti.analytics.reference.domain.RouteInfo;
import dev.pti.analytics.reference.domain.TripPattern;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.time.DayType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Date;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The ETA and OTP items of a recompute with the real adapters (DOC-23 §11.1): a forced run over the whole table, and one
 * scored day. The statistics SQL is covered by the store tests; here the item, its transaction and its merge.
 */
class EtaAndOtpRecomputeIT {

    private static final LocalDate SATURDAY = LocalDate.parse("2035-05-05");

    private final WarehouseSupport db = new WarehouseSupport();
    private final JdbcTemplate jdbc = new JdbcTemplate(db.dataSource);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final String route = "RC3-" + UUID.randomUUID().toString().substring(0, 8);
    private final TripUpdateRows facts = new TripUpdateRows(jdbc, route);
    private final BusinessClock clock =
            new BusinessClock(Clock.fixed(Instant.parse("2035-05-06T15:30:00Z"), ZoneOffset.UTC), Duration.ZERO);
    private final JdbcEtaAggregateStore etaStore = new JdbcEtaAggregateStore(db.jdbc);

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

    private final DetectorRecomputeService service = service();

    private DetectorRecomputeService service() {
        RunReporter reporter = new RunReporter(new MicrometerAnalyticsMetrics(meters));
        JdbcAdvisoryLock lock = new JdbcAdvisoryLock(db.jdbc);
        JdbcTransactionLimits limits = new JdbcTransactionLimits(db.jdbc);
        EtaAggregator aggregator = new EtaAggregator(
                etaStore,
                reference,
                lock,
                limits,
                db.tx,
                reporter,
                AnalyticsPropertiesFixtures.defaults().eta().toSettings());
        OtpScorecardCalculator calculator = new OtpScorecardCalculator(
                new JdbcOtpScorecardStore(db.jdbc),
                reference,
                lock,
                limits,
                db.tx,
                reporter,
                clock,
                AnalyticsPropertiesFixtures.defaults().otp().toSettings());
        return new DetectorRecomputeService(
                List.of(
                        new EtaRecompute(
                                new EtaRunPlanner(etaStore, reference, clock),
                                aggregator,
                                reference,
                                db.tx,
                                limits,
                                clock),
                        new OtpRecompute(calculator, reference, clock)),
                new MicrometerRecomputeMetrics(meters));
    }

    @AfterEach
    void cleanUp() {
        facts.clear();
        jdbc.update("DELETE FROM insight.insight_eta_prediction WHERE route_id = ?", route);
        jdbc.update("DELETE FROM insight.insight_otp_scorecard WHERE route_id = ?", route);
        jdbc.update("DELETE FROM ops.etl_checkpoint WHERE checkpoint_key = 'analytics.eta'");
    }

    private static Instant at(String instant) {
        return Instant.parse(instant);
    }

    @Test
    void theEtaItemRunsForcedRebuildsTheTableAndStoresTheCheckpoint() {
        facts.arrival(SATURDAY, at("2035-05-05T22:10:00Z"), 60).insert();
        facts.arrival(SATURDAY, at("2035-05-05T22:20:00Z"), 120).insert();
        jdbc.update("""
                INSERT INTO insight.insight_eta_prediction (route_id, stop_id, day_of_week, hour_of_day,
                  avg_delay_seconds, median_delay_seconds, p90_delay_seconds, sample_count, window_start, window_end,
                  computed_at, batch_id)
                VALUES (?, 'OLD', 1, 1, 1.0, 1, 1, 1, DATE '2035-04-01', DATE '2035-05-05',
                        TIMESTAMPTZ '2035-05-05 12:00:00Z', ?)""", route, UUID.randomUUID());
        // The last aggregation saw this very data: only a forced run would redo it.
        Instant hour = at("2035-05-06T15:00:00Z");
        String watermark = etaStore.sourceWatermark(ServiceDates.covering(hour, hour, reference.agencyZone()));
        etaStore.saveCheckpoint(watermark, at("2035-05-06T14:00:00Z"), null);

        List<WorkItem> items =
                service.plan(Set.of(Detector.ETA), at("2035-05-05T00:00:00Z"), at("2035-05-05T23:59:00Z"));
        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.scope()).isEqualTo("2035-05-06T15:00:00Z");
            assertThat(item.from()).isEqualTo(at("2035-05-06T15:00:00Z"));
        });
        DetectorStats stats = service.execute(items.getFirst());

        assertThat(stats.scopes()).isEqualTo(1);
        assertThat(stats.upserted()).isGreaterThanOrEqualTo(1);
        assertThat(stats.deleted()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForList(
                        "SELECT stop_id FROM insight.insight_eta_prediction WHERE route_id = ?", String.class, route))
                .containsExactly("S1");
        assertThat(meters.get("pti.analytics.runs")
                        .tag("detector", "eta")
                        .tag("trigger", "recompute")
                        .tag("outcome", "ok")
                        .counter()
                        .count())
                .isGreaterThanOrEqualTo(1);
        assertThat(meters.get("pti.analytics.recompute.rows")
                        .tag("detector", "eta")
                        .tag("op", "deleted")
                        .counter()
                        .count())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void theOtpItemsScoreOneDateEachAndTheDayHoldsTheRoutesRow() {
        facts.arrival(SATURDAY, at("2035-05-05T22:10:00Z"), 60).insert();
        facts.arrival(SATURDAY, at("2035-05-05T22:20:00Z"), 900).insert();

        List<WorkItem> items =
                service.plan(Set.of(Detector.OTP), at("2035-05-05T18:00:00Z"), at("2035-05-05T23:00:00Z"));

        assertThat(items).extracting(WorkItem::scope).containsExactly("2035-05-04", "2035-05-05");
        DetectorStats friday = service.execute(items.get(0));
        DetectorStats saturday = service.execute(items.get(1));
        assertThat(saturday.upserted()).isGreaterThanOrEqualTo(1);
        assertThat(friday.scopes() + saturday.scopes()).isEqualTo(2);
        assertThat(jdbc.queryForMap(
                        "SELECT on_time_count, late_count, observation_count FROM insight.insight_otp_scorecard"
                                + " WHERE route_id = ? AND service_date = ?",
                        route,
                        Date.valueOf(SATURDAY)))
                .containsEntry("on_time_count", 1)
                .containsEntry("late_count", 1)
                .containsEntry("observation_count", 2);
    }
}
