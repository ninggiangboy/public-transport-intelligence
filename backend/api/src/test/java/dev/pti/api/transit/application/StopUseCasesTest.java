package dev.pti.api.transit.application;

import static dev.pti.apitest.TransitData.route;
import static dev.pti.apitest.TransitData.stop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.api.platform.application.RequireActiveFeed;
import dev.pti.api.platform.domain.Caller;
import dev.pti.api.platform.domain.NotFoundException;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;
import dev.pti.api.platform.domain.Role;
import dev.pti.api.platform.domain.WithAsOf;
import dev.pti.api.transit.domain.BoundingBox;
import dev.pti.api.transit.domain.StopDetail;
import dev.pti.api.transit.domain.StopDisruption;
import dev.pti.api.transit.domain.StopMatch;
import dev.pti.api.transit.domain.StopRouteRef;
import dev.pti.api.transit.domain.StopRoutes;
import dev.pti.apitest.DirectTransactions;
import dev.pti.apitest.InMemoryTransit;
import dev.pti.common.events.Audience;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@code GET /stops} and {@code GET /stops/{stopId}} (DOC-32 E-06, E-07): the search modes and the disruption view. */
class StopUseCasesTest {

    private static final Caller VIEWER = new Caller("viewer", "Viewer", Set.of(Role.VIEWER), null);

    private final InMemoryTransit transit = new InMemoryTransit();
    private final DirectTransactions tx = new DirectTransactions();

    private SearchStops search() {
        return new SearchStops(
                new RequireActiveFeed(transit.activeFeed), transit.stopReader, transit.stopRoutesReader, tx);
    }

    private GetStop get() {
        return new GetStop(
                new RequireActiveFeed(transit.activeFeed),
                transit.stopReader,
                transit.stopRoutesReader,
                transit.routeCatalog,
                transit.stopDisruptions,
                tx);
    }

    @BeforeEach
    void network() {
        transit.routes.add(route("901", 0, 1));
        transit.routes.add(route("18", 3, 24));
        transit.stops.put("100", stop("100", "Lake St & Nicollet", -93.27, 44.94));
        transit.stops.put("200", stop("200", "Nicollet Ave & 46th", -93.28, 44.92));
        transit.stops.put("300", stop("300", "Airport Terminal", -93.21, 44.88));
        transit.stopRoutes = new StopRoutes(Map.of(
                "100", List.of(new StopRouteRef("18", List.of("Downtown"))),
                "200",
                        List.of(
                                new StopRouteRef("18", List.of("Downtown", "Nicollet & 66th")),
                                new StopRouteRef("901", List.of("Mall of America"))),
                "300", List.of(new StopRouteRef("901", List.of()))));
    }

    // ------------------------------------------------------------------------------------------------ E-06

    @Test
    @DisplayName("q: ranked stops with their route ids, no cursor, as fresh as the feed activation")
    void textSearch() {
        WithAsOf<Page<StopMatch>> result = search().execute(new StopSearch.ByText("nicollet", 20));

        // The name that starts with q comes before the one that only contains it.
        assertThat(result.value().items())
                .extracting(match -> match.stop().stopId())
                .containsExactly("200", "100");
        assertThat(result.value().items().get(0).routeIds()).containsExactly("18", "901");
        assertThat(result.value().next()).isNull();
        assertThat(result.asOf()).isEqualTo(InMemoryTransit.FEED.activatedAt());
    }

    @Test
    @DisplayName("q: a stop code match comes before the names")
    void codeFirst() {
        transit.stops.put("Nicollet", stop("Nicollet", "Zzz", -93.0, 44.0));

        List<StopMatch> items =
                search().execute(new StopSearch.ByText("Nicollet", 20)).value().items();

        assertThat(items.get(0).stop().stopId()).isEqualTo("Nicollet");
    }

    @Test
    @DisplayName("bbox: stops in the window, paged by stop id with a cursor to the next page")
    void areaSearchPages() {
        BoundingBox box = new BoundingBox(-93.30, 44.80, -93.20, 45.00);

        Page<StopMatch> first = search().execute(new StopSearch.ByArea(box, null, PageRequest.first(1)))
                .value();
        assertThat(first.items()).extracting(match -> match.stop().stopId()).containsExactly("100");
        assertThat(first.next().keys()).containsExactly("100");

        Page<StopMatch> second = search().execute(new StopSearch.ByArea(box, null, new PageRequest(1, first.next())))
                .value();
        assertThat(second.items()).extracting(match -> match.stop().stopId()).containsExactly("200");

        Page<StopMatch> last = search().execute(new StopSearch.ByArea(box, null, new PageRequest(5, second.next())))
                .value();
        assertThat(last.items()).extracting(match -> match.stop().stopId()).containsExactly("300");
        assertThat(last.next()).isNull();
    }

    @Test
    @DisplayName("routeId: the stops of that route; a route that does not exist has none")
    void routeFilter() {
        List<StopMatch> route = search().execute(new StopSearch.ByArea(null, "901", PageRequest.first(10)))
                .value()
                .items();
        List<StopMatch> none = search().execute(new StopSearch.ByArea(null, "zzz", PageRequest.first(10)))
                .value()
                .items();

        assertThat(route).extracting(match -> match.stop().stopId()).containsExactly("200", "300");
        assertThat(none).isEmpty();
    }

    @Test
    @DisplayName("bbox and routeId together narrow each other")
    void windowAndRoute() {
        BoundingBox box = new BoundingBox(-93.30, 44.90, -93.25, 45.00);

        List<StopMatch> items = search().execute(new StopSearch.ByArea(box, "901", PageRequest.first(10)))
                .value()
                .items();

        assertThat(items).extracting(match -> match.stop().stopId()).containsExactly("200");
    }

    // ------------------------------------------------------------------------------------------------ E-07

    @Test
    @DisplayName("The routes of a stop come in the order of the route list, with their colours and headsigns")
    void routesOfAStop() {
        StopDetail detail = get().execute(Caller.anonymous(), "200").value();

        assertThat(detail.stop().name()).isEqualTo("Nicollet Ave & 46th");
        assertThat(detail.routes()).extracting(route -> route.routeId()).containsExactly("901", "18");
        assertThat(detail.routes().get(1).headsigns()).containsExactly("Downtown", "Nicollet & 66th");
        assertThat(detail.routes().get(1).color()).isEqualTo("0053A0");
    }

    @Test
    @DisplayName("EP-09 an anonymous caller asks for PUBLIC disruptions only; a viewer for every audience")
    void audienceByCaller() {
        get().execute(Caller.anonymous(), "200");
        assertThat(transit.lastAudiences).containsExactly(Audience.PUBLIC);

        get().execute(VIEWER, "200");
        assertThat(transit.lastAudiences).containsExactlyInAnyOrder(Audience.values());
        assertThat(transit.lastDisruptionRoutes).containsExactly("901", "18");
    }

    @Test
    @DisplayName("Disruptions come through as the reader gives them, and a stop without routes asks for none")
    void disruptions() {
        transit.disruptions.add(
                new StopDisruption("a1", "d1", "18", 0, 1, "Delays", Instant.parse("2026-09-29T20:58:00Z")));
        assertThat(get().execute(Caller.anonymous(), "200").value().activeDisruptions())
                .hasSize(1);

        transit.stops.put("400", stop("400", "Nowhere", -93.0, 44.0));
        transit.lastDisruptionRoutes = null;
        assertThat(get().execute(Caller.anonymous(), "400").value().activeDisruptions())
                .isEmpty();
        assertThat(transit.lastDisruptionRoutes).isNull();
    }

    @Test
    @DisplayName("An unknown stop is a 404")
    void unknownStop() {
        assertThatThrownBy(() -> get().execute(Caller.anonymous(), "nope")).isInstanceOf(NotFoundException.class);
    }
}
