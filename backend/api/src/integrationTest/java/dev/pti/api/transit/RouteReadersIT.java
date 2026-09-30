package dev.pti.api.transit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.api.transit.adapter.out.jdbc.JdbcEtaProfileReader;
import dev.pti.api.transit.adapter.out.jdbc.JdbcRouteCatalogReader;
import dev.pti.api.transit.adapter.out.jdbc.JdbcRouteDelayReader;
import dev.pti.api.transit.adapter.out.jdbc.JdbcRouteDetailReader;
import dev.pti.api.transit.application.port.RouteDelayReader;
import dev.pti.api.transit.domain.BucketKey;
import dev.pti.api.transit.domain.BucketSize;
import dev.pti.api.transit.domain.DelayBucket;
import dev.pti.api.transit.domain.DirectionPattern;
import dev.pti.api.transit.domain.EtaRow;
import dev.pti.api.transit.domain.GeoPoint;
import dev.pti.api.transit.domain.GeometrySource;
import dev.pti.api.transit.domain.OnTimeTolerance;
import dev.pti.api.transit.domain.PatternStop;
import dev.pti.api.transit.domain.RouteCatalog;
import dev.pti.api.transit.domain.RouteDetail;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** E-01 to E-04 against the real warehouse: the readers of routes, route patterns, delays and the ETA profile. */
class RouteReadersIT extends TransitIntegrationSupport {

    private static final OnTimeTolerance TOLERANCE =
            new OnTimeTolerance(Duration.ofSeconds(300), Duration.ofSeconds(300));

    @Autowired
    private JdbcRouteCatalogReader catalog;

    @Autowired
    private JdbcRouteDetailReader details;

    @Autowired
    private JdbcRouteDelayReader delays;

    @Autowired
    private JdbcEtaProfileReader eta;

    // ------------------------------------------------------------------------------------------------ E-01

    @Test
    @DisplayName("E-01 the routes of the feed by sort order with nulls last, then name, with the GTFS columns")
    void routeCatalog() {
        installNetwork();

        RouteCatalog result = catalog.read(activeFeed());

        assertThat(result.feedVersionId()).isEqualTo(feedVersionId);
        assertThat(result.routes()).extracting(route -> route.routeId()).containsExactly("901", "18", "77");
        assertThat(result.routes().get(1).shortName()).isEqualTo("18");
        assertThat(result.routes().get(1).longName()).isEqualTo("Nicollet Av - Nicollet Mall - 1st Av");
        assertThat(result.routes().get(1).color()).isEqualTo("771473");
        assertThat(result.routes().get(1).routeType()).isEqualTo(3);
        assertThat(result.routes().get(1).typicalHeadwaySeconds()).isEqualTo(600);
        assertThat(result.routes().get(0).shortName()).isNull();
        assertThat(result.routes().get(2).sortOrder()).isNull();
    }

    // ------------------------------------------------------------------------------------------------ E-02

    @Test
    @DisplayName("EP-02 three shapes in direction 0: the most trips wins, the smaller id on a tie, the stops are the")
    void patternOfTheBusiestShape() {
        installNetwork();

        RouteDetail detail = details.find(activeFeed(), "18").orElseThrow();

        assertThat(detail.route().routeId()).isEqualTo("18");
        assertThat(detail.directions()).hasSize(2);
        DirectionPattern north = detail.direction(0).orElseThrow();
        // shB and shC both have 4 trips: shB has the smaller id. Its trips: b1 (3 stops), b2, b3 (5), b4 (4).
        assertThat(north.shapeId()).isEqualTo("shB");
        assertThat(north.tripCount()).isEqualTo(4);
        assertThat(north.label()).isEqualTo("NB");
        assertThat(north.headsign()).isEqualTo("Downtown");
        assertThat(north.geometrySource()).isEqualTo(GeometrySource.SHAPE);
        assertThat(north.stops()).extracting(PatternStop::stopId).containsExactly("s1", "s2", "s3", "s4", "s5");
        assertThat(north.stops()).extracting(PatternStop::stopSequence).containsExactly(1, 2, 3, 4, 5);
        assertThat(north.stops().get(0).name()).isEqualTo("Nicollet Ave & 46th St");
        assertThat(north.stops().get(0).code()).isEqualTo("1001");
        assertThat(north.stops().get(0).lat()).isEqualTo(44.9204);
        assertThat(north.stops().get(0).lon()).isEqualTo(-93.2780);
        DirectionPattern south = detail.direction(1).orElseThrow();
        assertThat(south.shapeId()).isEqualTo("shD");
        assertThat(south.headsign()).isEqualTo("Airport");
        assertThat(south.stops()).extracting(PatternStop::stopId).containsExactly("s5", "s3", "s1");
    }

    @Test
    @DisplayName("E-02 the 101 collinear points of a shape with one corner are simplified to start, corner and end")
    void shapeIsSimplified() {
        installNetwork();

        List<GeoPoint> line = details.find(activeFeed(), "18")
                .orElseThrow()
                .direction(0)
                .orElseThrow()
                .geometry();

        assertThat(line)
                .containsExactly(
                        new GeoPoint(-93.278, 44.9204), new GeoPoint(-93.278, 44.9454), new GeoPoint(-93.253, 44.9454));
    }

    @Test
    @DisplayName("EP-03 a feed without shapes: the line joins the stops of the representative trip")
    void stopsGeometryWithoutShapes() {
        installNetwork();

        DirectionPattern loop =
                details.find(activeFeed(), "77").orElseThrow().direction(0).orElseThrow();

        assertThat(loop.geometrySource()).isEqualTo(GeometrySource.STOPS);
        assertThat(loop.shapeId()).isNull();
        assertThat(loop.label()).isNull();
        assertThat(loop.headsign()).isEqualTo("Loop");
        assertThat(loop.geometry()).containsExactly(new GeoPoint(-93.278, 44.9204), new GeoPoint(-93.278, 44.948));
        assertThat(loop.stops()).extracting(PatternStop::stopId).containsExactly("s1", "s2", "s3");
    }

    @Test
    @DisplayName("E-02 a route that is not in the feed is empty, and is not cached")
    void unknownRoute() {
        installNetwork();

        assertThat(details.find(activeFeed(), "nope")).isEmpty();
        assertThat(caches.cache("route-detail").estimatedSize()).isZero();
        assertThat(details.find(activeFeed(), "18")).isPresent();
        assertThat(caches.cache("route-detail").estimatedSize()).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------------ E-03

    private void delayFacts() {
        installNetwork();
        partitions("2026-09-28", "2026-11-08");
        asOwner(
                // Tuesday in CDT: 16:10 and 16:40 local.
                tripUpdate("2026-09-29", "u1", 1, "2026-09-29 21:10:00+00", 60),
                tripUpdate("2026-09-29", "u2", 1, "2026-09-29 21:40:00+00", 120),
                // Tuesday in CST, an hour later in UTC for the same local time.
                tripUpdate("2026-11-03", "u3", 1, "2026-11-03 22:10:00+00", 180),
                tripUpdate("2026-11-03", "u4", 1, "2026-11-03 22:40:00+00", 600),
                // The day the clocks go back: 01:30 happens twice, 06:30Z in CDT and 07:30Z in CST.
                tripUpdate("2026-11-01", "u5", 1, "2026-11-01 06:30:00+00", 0),
                tripUpdate("2026-11-01", "u6", 1, "2026-11-01 07:30:00+00", 300),
                // Not counted: not observed, skipped, no delay, another route.
                tripUpdate("2026-09-29", "n1", 1, "18", 0, "2026-09-29 21:15:00+00", 900, false, "SCHEDULED"),
                tripUpdate("2026-09-29", "n2", 1, "18", 0, "2026-09-29 21:16:00+00", 700, true, "SKIPPED"),
                tripUpdate("2026-09-29", "n3", 1, "18", 0, "2026-09-29 21:17:00+00", null, true, "SCHEDULED"),
                tripUpdate("2026-09-29", "n4", 1, "901", 0, "2026-09-29 21:18:00+00", 40, true, "SCHEDULED"),
                // Direction 1.
                tripUpdate("2026-09-29", "u7", 1, "18", 1, "2026-09-29 21:20:00+00", 30, true, "SCHEDULED"));
    }

    private List<DelayBucket> read(BucketSize size, String from, String to, Integer direction) {
        return delays.read(new RouteDelayReader.Request(
                activeFeed(), "18", size, Instant.parse(from), Instant.parse(to), direction, TOLERANCE));
    }

    private static void assertBucket(
            DelayBucket bucket, String avg, int median, int p90, long count, String onTimePercentage) {
        assertThat(bucket.avgDelaySeconds()).isEqualTo(new BigDecimal(avg));
        assertThat(bucket.medianDelaySeconds()).isEqualTo(median);
        assertThat(bucket.p90DelaySeconds()).isEqualTo(p90);
        assertThat(bucket.observationCount()).isEqualTo(count);
        assertThat(bucket.onTimePercentage()).isEqualTo(new BigDecimal(onTimePercentage));
    }

    @Test
    @DisplayName("EP-04 hour-of-week: the ISO weekday and local hour, across the change of clocks")
    void hourOfWeek() {
        delayFacts();

        List<DelayBucket> buckets = read(BucketSize.HOUR_OF_WEEK, "2026-09-28T00:00:00Z", "2026-11-08T00:00:00Z", 0);

        // Sunday 01:xx local is 7 * 100 + 1, Tuesday 16:xx is 2 * 100 + 16: the CDT and CST rows share a bucket.
        assertThat(buckets)
                .extracting(DelayBucket::key)
                .containsExactly(new BucketKey.WeekHour(2, 16), new BucketKey.WeekHour(7, 1));
        // 60, 120, 180, 600: mean 240, median (percentile_disc 0.5) 120, p90 600, three of four within 300 s.
        assertBucket(buckets.get(0), "240.0", 120, 600, 4, "75.00");
        // 0 and 300: the 300 s boundary is on time.
        assertBucket(buckets.get(1), "150.0", 0, 300, 2, "100.00");
    }

    @Test
    @DisplayName("E-03 daily buckets by local service date")
    void daily() {
        delayFacts();

        List<DelayBucket> buckets = read(BucketSize.DAY, "2026-09-28T00:00:00Z", "2026-11-08T00:00:00Z", 0);

        assertThat(buckets)
                .extracting(DelayBucket::key)
                .containsExactly(
                        new BucketKey.Daily(LocalDate.parse("2026-09-29")),
                        new BucketKey.Daily(LocalDate.parse("2026-11-01")),
                        new BucketKey.Daily(LocalDate.parse("2026-11-03")));
        assertBucket(buckets.get(0), "90.0", 60, 120, 2, "100.00");
        assertBucket(buckets.get(1), "150.0", 0, 300, 2, "100.00");
        assertBucket(buckets.get(2), "390.0", 180, 600, 2, "50.00");
    }

    @Test
    @DisplayName("E-03 hourly buckets start at the UTC hour of the scheduled arrival")
    void hourly() {
        delayFacts();

        List<DelayBucket> buckets = read(BucketSize.HOUR, "2026-09-28T00:00:00Z", "2026-11-08T00:00:00Z", 0);

        assertThat(buckets)
                .extracting(DelayBucket::key)
                .containsExactly(
                        new BucketKey.Hourly(Instant.parse("2026-09-29T21:00:00Z")),
                        new BucketKey.Hourly(Instant.parse("2026-11-01T06:00:00Z")),
                        new BucketKey.Hourly(Instant.parse("2026-11-01T07:00:00Z")),
                        new BucketKey.Hourly(Instant.parse("2026-11-03T22:00:00Z")));
        assertBucket(buckets.get(0), "90.0", 60, 120, 2, "100.00");
    }

    @Test
    @DisplayName(
            "E-03 the range is on the scheduled arrival, from inclusive to exclusive; direction 1 and both directions")
    void rangeAndDirection() {
        delayFacts();

        List<DelayBucket> exact = read(BucketSize.HOUR, "2026-09-29T21:10:00Z", "2026-09-29T21:40:00Z", 0);
        assertThat(exact).hasSize(1);
        assertBucket(exact.get(0), "60.0", 60, 60, 1, "100.00");

        List<DelayBucket> other = read(BucketSize.HOUR, "2026-09-28T00:00:00Z", "2026-09-30T00:00:00Z", 1);
        assertThat(other).hasSize(1);
        assertBucket(other.get(0), "30.0", 30, 30, 1, "100.00");

        List<DelayBucket> both = read(BucketSize.HOUR, "2026-09-28T00:00:00Z", "2026-09-30T00:00:00Z", null);
        assertBucket(both.get(0), "70.0", 60, 120, 3, "100.00");
    }

    @Test
    @DisplayName("E-03 a range without observations has no buckets")
    void noObservations() {
        delayFacts();

        assertThat(read(BucketSize.DAY, "2026-10-01T00:00:00Z", "2026-10-20T00:00:00Z", 0))
                .isEmpty();
    }

    // ------------------------------------------------------------------------------------------------ E-04

    @Test
    @DisplayName("E-04 the ETA rows of a route for one weekday and hour, and only those")
    void etaRows() {
        installNetwork();
        asOwner("""
                INSERT INTO insight.insight_eta_prediction (route_id, stop_id, day_of_week, hour_of_day,
                  avg_delay_seconds, median_delay_seconds, p90_delay_seconds, sample_count, window_start, window_end,
                  computed_at, batch_id)
                VALUES
                  ('18', 's1', 2, 16, 64.2, 51, 170, 36, DATE '2026-09-01', DATE '2026-09-28',
                   TIMESTAMPTZ '2026-09-29 21:05:12.123456Z', gen_random_uuid()),
                  ('18', 's3', 2, 16, -10.5, -8, 30, 5, DATE '2026-09-01', DATE '2026-09-28',
                   TIMESTAMPTZ '2026-09-29 21:05:12Z', gen_random_uuid()),
                  ('18', 's1', 2, 17, 99.0, 90, 200, 12, DATE '2026-09-01', DATE '2026-09-28',
                   TIMESTAMPTZ '2026-09-29 21:05:12Z', gen_random_uuid()),
                  ('901', 's6', 2, 16, 1.0, 1, 1, 1, DATE '2026-09-01', DATE '2026-09-28',
                   TIMESTAMPTZ '2026-09-29 21:05:12Z', gen_random_uuid())""");

        List<EtaRow> rows = eta.read("18", 2, 16);

        assertThat(rows).extracting(EtaRow::stopId).containsExactlyInAnyOrder("s1", "s3");
        EtaRow s1 = rows.stream()
                .filter(row -> row.stopId().equals("s1"))
                .findFirst()
                .orElseThrow();
        assertThat(s1.avgDelaySeconds()).isEqualTo(new BigDecimal("64.2"));
        assertThat(s1.medianDelaySeconds()).isEqualTo(51);
        assertThat(s1.p90DelaySeconds()).isEqualTo(170);
        assertThat(s1.sampleCount()).isEqualTo(36);
        assertThat(s1.windowStart()).isEqualTo(LocalDate.parse("2026-09-01"));
        assertThat(s1.windowEnd()).isEqualTo(LocalDate.parse("2026-09-28"));
        assertThat(s1.computedAt()).isEqualTo(Instant.parse("2026-09-29T21:05:12.123456Z"));
        assertThat(eta.read("18", 3, 16)).isEmpty();
    }
}
