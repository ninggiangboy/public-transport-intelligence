package dev.pti.etl.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import dev.pti.analytics.event.application.port.AnalyticsEventSink;
import dev.pti.analytics.reference.adapter.out.jdbc.JdbcAnalyticsReferenceCache;
import dev.pti.analytics.reference.application.port.ActiveFeedVersion;
import dev.pti.analytics.reference.application.port.AnalyticsReferenceCache;
import dev.pti.analytics.reference.domain.PatternStop;
import dev.pti.analytics.reference.domain.RouteInfo;
import dev.pti.analytics.reference.domain.TripPattern;
import dev.pti.common.time.DayType;
import dev.pti.etl.analytics.adapter.in.event.AnalyticsDispatcher;
import dev.pti.etl.batch.BatchContextSupport;
import dev.pti.etl.batch.JobParams;
import dev.pti.etl.batch.PtiJob;
import dev.pti.etl.batch.PtiJobLauncher;
import dev.pti.etl.gtfs.FeedBuilder;
import dev.pti.etl.gtfs.FeedContext;
import dev.pti.etl.gtfs.FetchFeedTasklet;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link AnalyticsReferenceCache} against the mini GTFS feed (DOC-23 §3), which {@code GtfsStaticLoadJob} loads into
 * the migrated warehouse, and the analytics wiring of the {@code batch} profile: the event sink and the reference
 * cache are there, the dispatcher is not.
 */
class ReferenceCacheIT extends BatchContextSupport {

    @Autowired
    PtiJobLauncher launcher;

    @Autowired
    ApplicationContext context;

    private final AtomicReference<OptionalLong> activeFeed = new AtomicReference<>(OptionalLong.empty());
    private AnalyticsReferenceCache cache;

    @BeforeEach
    void cache() {
        ActiveFeedVersion version = activeFeed::get;
        cache = new JdbcAnalyticsReferenceCache(JdbcClient.create(jdbc.getDataSource()), version);
    }

    private long load(FeedBuilder feed) throws Exception {
        Path zip = feed.write(FEEDS);
        JobExecution execution = awaitEnd(launcher.start(
                PtiJob.GTFS_STATIC_LOAD,
                JobParams.identity(PtiJob.GTFS_STATIC_LOAD, "manual:" + UUID.randomUUID())
                        .addString(FetchFeedTasklet.SOURCE_URI, zip.toUri().toString(), false)
                        .addString(FetchFeedTasklet.ALLOW_REACTIVATE, "false", false)
                        .toJobParameters()));
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo("COMPLETED");
        return execution.getExecutionContext().getLong(FeedContext.FEED_VERSION_ID);
    }

    private long activate(FeedBuilder feed) throws Exception {
        long version = load(feed);
        activeFeed.set(OptionalLong.of(version));
        return version;
    }

    @Test
    void theRoutesOfTheActiveFeedCarryTypeLabelAndDirectionLabels() throws Exception {
        long version = activate(FeedBuilder.mini());

        assertThat(cache.hasActiveFeed()).isTrue();
        assertThat(cache.feedVersionId()).isEqualTo(version);
        assertThat(cache.agencyZone()).isEqualTo(ZoneId.of("America/Chicago"));

        RouteInfo bus = cache.route("18").orElseThrow();
        assertThat(bus.routeType()).isEqualTo(3);
        assertThat(bus.label()).as("route_short_name").isEqualTo("18");
        assertThat(bus.directionLabels()).containsExactlyInAnyOrderEntriesOf(Map.of(0, "NB", 1, "SB"));

        RouteInfo rail = cache.route("901").orElseThrow();
        assertThat(rail.routeType()).isZero();
        assertThat(rail.label()).as("no short name: the route id").isEqualTo("901");

        assertThat(cache.route("no-such-route")).isEmpty();
    }

    @Test
    void aTripPatternHasEveryStopOfTheTripWithGtfsSecondsAndShapeDistance() throws Exception {
        long version = activate(FeedBuilder.mini());
        String tripId = jdbc.queryForObject(
                "SELECT trip_id FROM dw.gtfs_trip WHERE feed_version_id = ? AND route_id = '18' ORDER BY trip_id LIMIT 1",
                String.class,
                version);
        Long stops = jdbc.queryForObject(
                "SELECT count(*) FROM dw.gtfs_stop_time WHERE feed_version_id = ? AND trip_id = ?",
                Long.class,
                version,
                tripId);
        Map<String, Object> first = jdbc.queryForMap("""
                SELECT st.stop_sequence, st.stop_id, st.arrival_seconds, st.departure_seconds, s.lat, s.lon
                FROM dw.gtfs_stop_time st
                JOIN dw.dim_stop s ON s.feed_version_id = st.feed_version_id AND s.stop_id = st.stop_id
                WHERE st.feed_version_id = ? AND st.trip_id = ? ORDER BY st.stop_sequence LIMIT 1""", version, tripId);

        TripPattern pattern = cache.trip(tripId).orElseThrow();

        assertThat(pattern.tripId()).isEqualTo(tripId);
        assertThat(pattern.routeId()).isEqualTo("18");
        assertThat(pattern.stops()).hasSize(stops.intValue());
        PatternStop head = pattern.stops().getFirst();
        assertThat(head.stopSequence()).isEqualTo(first.get("stop_sequence"));
        assertThat(head.stopId()).isEqualTo(first.get("stop_id"));
        assertThat(head.arrivalSeconds()).isEqualTo(first.get("arrival_seconds"));
        assertThat(head.departureSeconds()).isEqualTo(first.get("departure_seconds"));
        assertThat(head.lat()).isEqualTo(first.get("lat"));
        assertThat(head.lon()).isEqualTo(first.get("lon"));
        assertThat(head.dist()).as("shape_dist_traveled of the first stop").isZero();
        assertThat(pattern.stops())
                .extracting(PatternStop::dist)
                .as("progress never goes backwards")
                .isSorted();
        assertThat(pattern.indexOfSequence(head.stopSequence())).isZero();
        assertThat(pattern.indexOfSequence(-5)).isEqualTo(-1);
    }

    @Test
    void aTripThatTheFeedDoesNotHaveIsEmpty() throws Exception {
        activate(FeedBuilder.mini());

        assertThat(cache.trip("no-such-trip")).isEmpty();
    }

    @Test
    void scheduledHeadwaysComeFromTheFeedAndAnAbsentOneIsEmpty() throws Exception {
        long version = activate(FeedBuilder.mini());
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT route_id, direction_id, day_type, hour_of_day, scheduled_headway_seconds
                FROM dw.route_headway
                WHERE feed_version_id = ? AND scheduled_headway_seconds IS NOT NULL
                ORDER BY route_id, direction_id, day_type, hour_of_day LIMIT 1""", version);

        OptionalInt headway = cache.scheduledHeadway(
                (String) row.get("route_id"),
                ((Number) row.get("direction_id")).intValue(),
                DayType.valueOf((String) row.get("day_type")),
                ((Number) row.get("hour_of_day")).intValue());

        assertThat(headway).hasValue(((Number) row.get("scheduled_headway_seconds")).intValue());
        assertThat(cache.scheduledHeadway("no-such-route", 0, DayType.WEEKDAY, 8))
                .isEmpty();
        Long nullHeadways = jdbc.queryForObject(
                "SELECT count(*) FROM dw.route_headway WHERE feed_version_id = ? AND scheduled_headway_seconds IS NULL",
                Long.class,
                version);
        if (nullHeadways > 0) {
            Map<String, Object> none = jdbc.queryForMap("""
                    SELECT route_id, direction_id, day_type, hour_of_day FROM dw.route_headway
                    WHERE feed_version_id = ? AND scheduled_headway_seconds IS NULL LIMIT 1""", version);
            assertThat(cache.scheduledHeadway(
                            (String) none.get("route_id"),
                            ((Number) none.get("direction_id")).intValue(),
                            DayType.valueOf((String) none.get("day_type")),
                            ((Number) none.get("hour_of_day")).intValue()))
                    .as("NULL headway: fewer than two trips that hour")
                    .isEmpty();
        }
    }

    @Test
    void theDayTypeComesFromDimDateAndFallsBackToTheRulesOutsideIt() throws Exception {
        activate(FeedBuilder.mini());

        assertThat(cache.dayType(LocalDate.parse("2026-09-29"))).isEqualTo(DayType.WEEKDAY);
        assertThat(cache.dayType(LocalDate.parse("2026-09-26"))).isEqualTo(DayType.SATURDAY);
        assertThat(cache.dayType(LocalDate.parse("2026-09-27"))).isEqualTo(DayType.SUNDAY_HOLIDAY);
        assertThat(cache.dayType(LocalDate.parse("2026-09-07"))).as("Labor Day").isEqualTo(DayType.SUNDAY_HOLIDAY);
        assertThat(cache.dayType(LocalDate.parse("2035-01-01")))
                .as("after dim_date, by DayType.of")
                .isEqualTo(DayType.SUNDAY_HOLIDAY);
    }

    @Test
    void aNewFeedVersionDropsEverythingAndLoadsAgain() throws Exception {
        long first = activate(FeedBuilder.mini());
        assertThat(cache.route("18").orElseThrow().label()).isEqualTo("18");

        long second =
                activate(FeedBuilder.mini().edit("routes.txt", text -> text.replace("\n18,0,18,", "\n18,0,18X,")));

        assertThat(second).isNotEqualTo(first);
        assertThat(cache.feedVersionId()).isEqualTo(second);
        assertThat(cache.route("18").orElseThrow().label())
                .as("reloaded for the new version")
                .isEqualTo("18X");
    }

    @Test
    void withoutAnActiveFeedTheCacheSaysSoAndRefusesToAnswer() {
        activeFeed.set(OptionalLong.empty());

        assertThat(cache.hasActiveFeed()).isFalse();
        assertThatIllegalStateException().isThrownBy(() -> cache.route("18"));
        assertThatIllegalStateException().isThrownBy(cache::feedVersionId);
    }

    @Test
    void theBatchProfileHasTheSinkAndTheCacheButNoDispatcher() {
        assertThat(context.getBeansOfType(AnalyticsEventSink.class)).hasSize(1);
        assertThat(context.getBeansOfType(AnalyticsReferenceCache.class)).hasSize(1);
        assertThat(context.getBeansOfType(AnalyticsDispatcher.class)).isEmpty();
        assertThat(context.containsBean("analyticsExecutor")).isFalse();
        assertThat(List.of(context.getBeanNamesForType(dev.pti.analytics.config.AnalyticsProperties.class)))
                .hasSize(1);
    }
}
