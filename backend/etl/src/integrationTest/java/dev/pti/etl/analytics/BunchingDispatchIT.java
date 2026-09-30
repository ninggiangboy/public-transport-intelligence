package dev.pti.etl.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.analytics.alert.adapter.out.jdbc.JdbcAlertWriter;
import dev.pti.analytics.bunching.adapter.out.jdbc.JdbcBunchingEpisodeWriter;
import dev.pti.analytics.bunching.adapter.out.jdbc.JdbcBunchingStateStore;
import dev.pti.analytics.bunching.adapter.out.jdbc.JdbcVehicleHistoryReader;
import dev.pti.analytics.bunching.adapter.out.metrics.MicrometerBunchingMetrics;
import dev.pti.analytics.bunching.application.BunchingDetector;
import dev.pti.analytics.config.AnalyticsPropertiesFixtures;
import dev.pti.analytics.core.adapter.out.jdbc.JdbcAdvisoryLock;
import dev.pti.analytics.core.adapter.out.jdbc.JdbcTransactionLimits;
import dev.pti.analytics.core.adapter.out.metrics.MicrometerAnalyticsMetrics;
import dev.pti.analytics.event.application.port.AnalyticsEventSink;
import dev.pti.analytics.event.domain.InsightEvent;
import dev.pti.analytics.reference.adapter.out.jdbc.JdbcAnalyticsReferenceCache;
import dev.pti.analytics.reference.application.port.AnalyticsReferenceCache;
import dev.pti.analytics.reference.domain.PatternStop;
import dev.pti.analytics.reference.domain.TripPattern;
import dev.pti.common.gtfs.GtfsTime;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.tx.TransactionRunner;
import dev.pti.etl.analytics.application.BatchCommit;
import dev.pti.etl.analytics.application.DispatchBatchAnalytics;
import dev.pti.etl.analytics.application.DispatchSummary;
import dev.pti.etl.batch.BatchContextSupport;
import dev.pti.etl.batch.JobParams;
import dev.pti.etl.batch.PtiJob;
import dev.pti.etl.batch.PtiJobLauncher;
import dev.pti.etl.core.EtlSource;
import dev.pti.etl.gtfs.FeedBuilder;
import dev.pti.etl.gtfs.FeedContext;
import dev.pti.etl.gtfs.FetchFeedTasklet;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Bunching through the use case that follows a micro-batch (DOC-23 §4.1, §18.8), with the mini GTFS feed that
 * {@code GtfsStaticLoadJob} loads as the ACTIVE feed: two buses run the same trip of route 18, the second a fifth of
 * a headway behind the first. The micro-batch reaches the detector through {@link DispatchBatchAnalytics}, and the
 * episode, its alert and the events come out; running the same data again adds nothing.
 *
 * <p>The Kafka leg of the dispatcher is covered by {@link AnalyticsDispatchIT}. The full stack with the simulator's
 * {@code bunching} scenario is checked on the running compose stack at M4.
 */
class BunchingDispatchIT extends BatchContextSupport {

    private static final String ROUTE = "18";
    private static final String TRIP = "1361959";
    private static final LocalDate DAY = LocalDate.parse("2026-09-29");

    @Autowired
    PtiJobLauncher launcher;

    @Autowired
    TransactionRunner transactions;

    private final Sink sink = new Sink();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM dw.fact_vehicle_position WHERE vehicle_id LIKE 'an2-%'");
        jdbc.update("DELETE FROM ops.alert_event WHERE dedup_key IN (SELECT 'bunching:' || id FROM"
                + " insight.insight_bus_bunching WHERE vehicle_leader LIKE 'an2-%')");
        jdbc.update("DELETE FROM insight.analytics_bunching_pair_state WHERE vehicle_leader LIKE 'an2-%'");
        jdbc.update("DELETE FROM insight.insight_bus_bunching WHERE vehicle_leader LIKE 'an2-%'");
        jdbc.update("DELETE FROM insight.analytics_bunching_cursor WHERE route_id = ?", ROUTE);
    }

    private long activateMiniFeed() throws Exception {
        Path zip = FeedBuilder.mini().write(FEEDS);
        JobExecution execution = awaitEnd(launcher.start(
                PtiJob.GTFS_STATIC_LOAD,
                JobParams.identity(PtiJob.GTFS_STATIC_LOAD, "manual:" + UUID.randomUUID())
                        .addString(FetchFeedTasklet.SOURCE_URI, zip.toUri().toString(), false)
                        .addString(FetchFeedTasklet.ALLOW_REACTIVATE, "false", false)
                        .toJobParameters()));
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        return execution.getExecutionContext().getLong(FeedContext.FEED_VERSION_ID);
    }

    /** Inserts a vehicle that follows the schedule of the trip, {@code lateSeconds} behind it, every 5 s. */
    private void drive(TripPattern trip, String vehicle, int lateSeconds, int fromSeconds, int toSeconds) {
        List<PatternStop> stops = trip.stops();
        for (int s = fromSeconds; s <= toSeconds; s += 5) {
            int scheduled = s - lateSeconds;
            int i = -1;
            for (int k = 0; k < stops.size() && stops.get(k).arrivalSeconds() <= scheduled; k++) {
                i = k;
            }
            if (i < 0) {
                continue;
            }
            PatternStop at = stops.get(i);
            boolean standing = scheduled <= at.departureSeconds() || i == stops.size() - 1;
            double lat = at.lat();
            double lon = at.lon();
            PatternStop heading = at;
            if (!standing) {
                heading = stops.get(i + 1);
                double fraction = (double) (scheduled - at.departureSeconds())
                        / (heading.arrivalSeconds() - at.departureSeconds());
                lat = at.lat() + fraction * (heading.lat() - at.lat());
                lon = at.lon() + fraction * (heading.lon() - at.lon());
            }
            jdbc.update(
                    """
                    INSERT INTO dw.fact_vehicle_position (service_date, vehicle_id, event_timestamp, trip_id, route_id,
                      direction_id, lat, lon, current_stop_sequence, stop_id, current_status, schema_version,
                      payload_hash, batch_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)""",
                    DAY,
                    vehicle,
                    Timestamp.from(GtfsTime.toInstant(DAY, s, java.time.ZoneId.of("America/Chicago"))),
                    TRIP,
                    ROUTE,
                    trip.directionId(),
                    lat,
                    lon,
                    heading.stopSequence(),
                    heading.stopId(),
                    standing ? "STOPPED_AT" : "IN_TRANSIT_TO",
                    UUID.randomUUID().toString().replace("-", "")
                            + UUID.randomUUID().toString().replace("-", ""),
                    UUID.randomUUID());
        }
    }

    private BunchingDetector detector(AnalyticsReferenceCache reference, Instant now) {
        JdbcClient client = JdbcClient.create(jdbc.getDataSource());
        return new BunchingDetector(
                new JdbcBunchingStateStore(client),
                new JdbcVehicleHistoryReader(client),
                new JdbcBunchingEpisodeWriter(client),
                new JdbcAlertWriter(client),
                reference,
                new JdbcAdvisoryLock(client),
                new JdbcTransactionLimits(client),
                new MicrometerAnalyticsMetrics(meters),
                new MicrometerBunchingMetrics(meters),
                transactions,
                new BusinessClock(Clock.fixed(now, ZoneOffset.UTC), Duration.ZERO),
                AnalyticsPropertiesFixtures.defaults().bunching().toThresholds());
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    @Test
    void aBunchedPairInAMicroBatchGivesOneEpisodeOneAlertAndTheEventsAndARerunAddsNothing() throws Exception {
        long version = activateMiniFeed();
        JdbcClient client = JdbcClient.create(jdbc.getDataSource());
        AnalyticsReferenceCache reference = new JdbcAnalyticsReferenceCache(client, () -> OptionalLong.of(version));
        TripPattern trip = reference.trip(TRIP).orElseThrow();
        int headway = jdbc.queryForObject("""
                SELECT scheduled_headway_seconds FROM dw.route_headway
                WHERE feed_version_id = ? AND route_id = ? AND direction_id = ? AND day_type = 'WEEKDAY'
                  AND hour_of_day = 16""", Integer.class, version, ROUTE, trip.directionId());
        // The follower runs a fifth of the headway behind: a gap far below half the headway.
        int late = Math.max(30, headway / 5);
        int from = trip.stops().getFirst().departureSeconds();
        int to = from + 7 * 60;
        drive(trip, "an2-L", 0, from, to);
        drive(trip, "an2-F", late, from, to);
        Instant last = GtfsTime.toInstant(DAY, to, java.time.ZoneId.of("America/Chicago"));
        Instant first = GtfsTime.toInstant(DAY, from, java.time.ZoneId.of("America/Chicago"));
        BunchingDetector detector = detector(reference, last.plusSeconds(10));
        DispatchBatchAnalytics dispatch = new DispatchBatchAnalytics(
                List.of(detector),
                new MicrometerAnalyticsMetrics(meters),
                sink,
                new BusinessClock(Clock.fixed(last.plusSeconds(11), ZoneOffset.UTC), Duration.ZERO));
        BatchCommit batch = new BatchCommit(
                UUID.randomUUID(),
                EtlSource.GTFS_RT_VEHICLE_POSITION,
                Set.of(ROUTE),
                first,
                first,
                last.plusSeconds(10));

        DispatchSummary summary = dispatch.execute(batch);

        assertThat(summary).isEqualTo(new DispatchSummary(1, 0));
        assertThat(sink.events()).extracting(InsightEvent::type).containsExactly("bunching.opened", "alert.created");
        assertThat(count("SELECT count(*) FROM insight.insight_bus_bunching WHERE vehicle_leader = 'an2-L'"))
                .isEqualTo(1);
        Map<String, Object> episode = jdbc.queryForMap("""
                SELECT route_id, vehicle_leader, vehicle_follower, scheduled_headway_seconds, threshold_seconds,
                       min_gap_seconds, status
                FROM insight.insight_bus_bunching WHERE vehicle_leader = 'an2-L'""");
        assertThat(episode)
                .containsEntry("route_id", ROUTE)
                .containsEntry("vehicle_follower", "an2-F")
                .containsEntry("scheduled_headway_seconds", headway)
                .containsEntry("status", "OPEN");
        assertThat(((Number) episode.get("min_gap_seconds")).intValue()).isLessThan(headway / 2);
        assertThat(count("SELECT count(*) FROM ops.alert_event WHERE type = 'BUNCHING' AND dedup_key IN"
                        + " (SELECT 'bunching:' || id FROM insight.insight_bus_bunching WHERE vehicle_leader ="
                        + " 'an2-L')"))
                .isEqualTo(1);

        // The same micro-batch again finds the route up to date; a rerun from a reset cursor changes nothing.
        assertThat(dispatch.execute(batch)).isEqualTo(new DispatchSummary(1, 0));
        jdbc.update("DELETE FROM insight.analytics_bunching_cursor WHERE route_id = ?", ROUTE);
        jdbc.update("DELETE FROM insight.analytics_bunching_pair_state WHERE route_id = ?", ROUTE);
        int published = sink.events().size();
        assertThat(dispatch.execute(batch)).isEqualTo(new DispatchSummary(1, 0));

        assertThat(sink.events()).as("no event for a repeat").hasSize(published);
        assertThat(count("SELECT count(*) FROM insight.insight_bus_bunching WHERE vehicle_leader = 'an2-L'"))
                .isEqualTo(1);
        assertThat(meters.get("pti.analytics.runs")
                        .tags("detector", "bunching", "trigger", "batch", "outcome", "ok")
                        .counter()
                        .count())
                .isEqualTo(2);
        assertThat(meters.get("pti.analytics.runs")
                        .tags("detector", "bunching", "trigger", "batch", "outcome", "noop")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    /** What the dispatcher would send to Kafka after the commit. */
    private static final class Sink implements AnalyticsEventSink {

        private final List<InsightEvent> events = new ArrayList<>();

        List<InsightEvent> events() {
            return events;
        }

        @Override
        public void publish(List<InsightEvent> published) {
            events.addAll(published);
        }
    }
}
