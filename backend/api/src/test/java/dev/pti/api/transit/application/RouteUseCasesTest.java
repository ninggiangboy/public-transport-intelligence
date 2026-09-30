package dev.pti.api.transit.application;

import static dev.pti.apitest.TransitData.route;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.api.platform.application.RequireActiveFeed;
import dev.pti.api.platform.domain.AsOfKind;
import dev.pti.api.platform.domain.NotFoundException;
import dev.pti.api.platform.domain.ServiceUnavailableException;
import dev.pti.api.platform.domain.WithAsOf;
import dev.pti.api.transit.domain.BucketSize;
import dev.pti.api.transit.domain.ConfidenceThresholds;
import dev.pti.api.transit.domain.DelayProfile;
import dev.pti.api.transit.domain.DelayProfileStop;
import dev.pti.api.transit.domain.DirectionPattern;
import dev.pti.api.transit.domain.EtaRow;
import dev.pti.api.transit.domain.GeometrySource;
import dev.pti.api.transit.domain.OnTimeTolerance;
import dev.pti.api.transit.domain.PatternStop;
import dev.pti.api.transit.domain.RouteCatalog;
import dev.pti.api.transit.domain.RouteDelays;
import dev.pti.api.transit.domain.RouteDetail;
import dev.pti.apitest.DirectTransactions;
import dev.pti.apitest.InMemoryTransit;
import dev.pti.testing.TestClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** E-01 to E-04 (DOC-32 §3): the route list, the route, its delays and its delay profile. */
class RouteUseCasesTest {

    private static final Instant NOW = Instant.parse("2026-09-29T21:20:00Z");
    private static final Instant ETA_AT = Instant.parse("2026-09-29T21:05:12Z");

    private final InMemoryTransit transit = new InMemoryTransit();
    private final DirectTransactions tx = new DirectTransactions();
    private final List<AsOfKind> asked = new ArrayList<>();

    @BeforeEach
    void network() {
        transit.routes.add(route("901", 0, 1));
        transit.routes.add(route("18", 3, 24));
        transit.routes.add(route("5", 3, 5));
        PatternStop first = new PatternStop("s1", "s1", "First", 44.9, -93.2, 1);
        PatternStop second = new PatternStop("s2", "s2", "Second", 44.91, -93.2, 2);
        PatternStop third = new PatternStop("s3", "s3", "Third", 44.92, -93.2, 3);
        transit.details.put(
                "18",
                new RouteDetail(
                        3,
                        transit.routes.get(1),
                        List.of(new DirectionPattern(
                                0,
                                "NB",
                                "Downtown",
                                312,
                                "shape",
                                GeometrySource.SHAPE,
                                List.of(),
                                List.of(first, second, third)))));
    }

    private RequireActiveFeed feed() {
        return new RequireActiveFeed(transit.activeFeed);
    }

    private GetRouteDelayProfile profile() {
        return new GetRouteDelayProfile(
                feed(),
                transit.routeDetail,
                transit.etaProfile,
                kind -> {
                    asked.add(kind);
                    return Optional.of(ETA_AT);
                },
                TestClock.at(NOW),
                new ConfidenceThresholds(10, 30),
                tx);
    }

    private static EtaRow eta(String stopId, String avg, int samples, String computedAt) {
        return new EtaRow(
                stopId,
                new BigDecimal(avg),
                51,
                170,
                samples,
                LocalDate.parse("2026-09-01"),
                LocalDate.parse("2026-09-28"),
                Instant.parse(computedAt));
    }

    // ------------------------------------------------------------------------------------------------ E-01, E-02

    @Test
    @DisplayName("E-01 the routes in the order the catalog gives them, as fresh as the feed activation")
    void listRoutes() {
        WithAsOf<RouteCatalog> all = new ListRoutes(feed(), transit.routeCatalog, tx).execute(Set.of());

        assertThat(all.value().feedVersionId()).isEqualTo(3);
        assertThat(all.value().routes()).extracting(r -> r.routeId()).containsExactly("901", "18", "5");
        assertThat(all.asOf()).isEqualTo(InMemoryTransit.FEED.activatedAt());
    }

    @Test
    @DisplayName("E-01 routeType keeps only routes of those GTFS types")
    void listRoutesByType() {
        RouteCatalog buses = new ListRoutes(feed(), transit.routeCatalog, tx)
                .execute(Set.of(3))
                .value();
        RouteCatalog both = new ListRoutes(feed(), transit.routeCatalog, tx)
                .execute(Set.of(0, 3))
                .value();
        RouteCatalog none = new ListRoutes(feed(), transit.routeCatalog, tx)
                .execute(Set.of(2))
                .value();

        assertThat(buses.routes()).extracting(r -> r.routeId()).containsExactly("18", "5");
        assertThat(both.routes()).hasSize(3);
        assertThat(none.routes()).isEmpty();
    }

    @Test
    @DisplayName("E-01, E-02 without an ACTIVE feed the answer is a 503")
    void noFeed() {
        transit.feed = null;

        assertThatThrownBy(() -> new ListRoutes(feed(), transit.routeCatalog, tx).execute(Set.of()))
                .isInstanceOf(ServiceUnavailableException.class);
        assertThatThrownBy(() -> new GetRoute(feed(), transit.routeDetail, tx).execute("18"))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    @DisplayName("E-02 a route that is in the feed, and a 404 for one that is not")
    void getRoute() {
        GetRoute getRoute = new GetRoute(feed(), transit.routeDetail, tx);

        assertThat(getRoute.execute("18").value().directions()).hasSize(1);
        assertThatThrownBy(() -> getRoute.execute("nope")).isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------------------------------------ E-03

    private GetRouteDelays delays() {
        return new GetRouteDelays(
                feed(),
                transit.routeCatalog,
                transit.routeDelays,
                kind -> {
                    asked.add(kind);
                    return Optional.of(NOW);
                },
                new OnTimeTolerance(Duration.ofSeconds(300), Duration.ofSeconds(300)),
                tx);
    }

    @Test
    @DisplayName(
            "E-03 the query reaches the reader with the feed, the range and the tolerance; as-of is the trip update")
    void delaysReachTheReader() {
        Instant from = NOW.minus(Duration.ofDays(7));

        WithAsOf<RouteDelays> result =
                delays().execute(new RouteDelaysQuery("18", from, NOW, BucketSize.HOUR_OF_WEEK, 1));

        assertThat(transit.lastDelayRequest.routeId()).isEqualTo("18");
        assertThat(transit.lastDelayRequest.bucket()).isEqualTo(BucketSize.HOUR_OF_WEEK);
        assertThat(transit.lastDelayRequest.directionId()).isEqualTo(1);
        assertThat(transit.lastDelayRequest.feed()).isEqualTo(InMemoryTransit.FEED);
        assertThat(transit.lastDelayRequest.tolerance().late()).isEqualTo(Duration.ofSeconds(300));
        assertThat(result.value().from()).isEqualTo(from);
        assertThat(result.value().tolerance().early()).isEqualTo(Duration.ofSeconds(300));
        assertThat(asked).containsExactly(AsOfKind.TRIP_UPDATE);
    }

    @Test
    @DisplayName("E-03 a route that is not in the feed is a 404, and nothing is read")
    void delaysUnknownRoute() {
        assertThatThrownBy(() -> delays().execute(
                                new RouteDelaysQuery("nope", NOW.minusSeconds(3600), NOW, BucketSize.DAY, null)))
                .isInstanceOf(NotFoundException.class);
        assertThat(transit.lastDelayRequest).isNull();
    }

    @Test
    @DisplayName("A delays query needs from before to and a direction of 0 or 1")
    void delaysQueryGuards() {
        assertThatThrownBy(() -> new RouteDelaysQuery("18", NOW, NOW, BucketSize.DAY, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RouteDelaysQuery("18", NOW.minusSeconds(1), NOW, BucketSize.DAY, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------------------------------------ E-04

    @Test
    @DisplayName("E-04 stops in pattern order; a stop without a row is NONE with no figures")
    void profileInPatternOrder() {
        transit.etaRows.add(eta("s3", "10.0", 5, "2026-09-29T21:05:12Z"));
        transit.etaRows.add(eta("s1", "64.2", 36, "2026-09-29T21:05:12Z"));

        DelayProfile profile = profile().execute("18", 0, 2, 16).value();

        assertThat(profile.stops()).extracting(DelayProfileStop::stopId).containsExactly("s1", "s2", "s3");
        assertThat(profile.stops()).extracting(stop -> stop.confidence().name()).containsExactly("HIGH", "NONE", "LOW");
        assertThat(profile.stops().get(1).eta()).isNull();
        assertThat(profile.stops().get(1).sampleCount()).isZero();
        assertThat(profile.stops().get(0).eta().avgDelaySeconds()).isEqualByComparingTo("64.2");
        assertThat(transit.lastEtaKey).isEqualTo("18:2:16");
    }

    @Test
    @DisplayName("E-04 the window and computedAt come from the row computed last; absent when there are no rows")
    void profileWindow() {
        transit.etaRows.add(eta("s1", "64.2", 36, "2026-09-29T20:05:12Z"));
        transit.etaRows.add(eta("s2", "64.2", 36, "2026-09-29T21:05:12Z"));

        DelayProfile withRows = profile().execute("18", 0, 2, 16).value();
        assertThat(withRows.computedAt()).isEqualTo(ETA_AT);
        assertThat(withRows.windowStart()).isEqualTo(LocalDate.parse("2026-09-01"));
        assertThat(withRows.windowEnd()).isEqualTo(LocalDate.parse("2026-09-28"));

        transit.etaRows.clear();
        DelayProfile empty = profile().execute("18", 0, 2, 16).value();
        assertThat(empty.computedAt()).isNull();
        assertThat(empty.windowStart()).isNull();
        assertThat(empty.stops())
                .allSatisfy(stop -> assertThat(stop.confidence().name()).isEqualTo("NONE"));
    }

    @Test
    @DisplayName("E-04 the weekday and hour default to business now in the timezone of the feed (Tuesday 16:xx CDT)")
    void profileDefaults() {
        WithAsOf<DelayProfile> result = profile().execute("18", 0, null, null);

        assertThat(result.value().dayOfWeek()).isEqualTo(2);
        assertThat(result.value().hourOfDay()).isEqualTo(16);
        assertThat(transit.lastEtaKey).isEqualTo("18:2:16");
        assertThat(asked).containsExactly(AsOfKind.ETA_PREDICTION);
    }

    @Test
    @DisplayName("E-04 a route or a direction that does not exist is a 404")
    void profileNotFound() {
        assertThatThrownBy(() -> profile().execute("nope", 0, 2, 16)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> profile().execute("18", 1, 2, 16)).isInstanceOf(NotFoundException.class);
    }
}
