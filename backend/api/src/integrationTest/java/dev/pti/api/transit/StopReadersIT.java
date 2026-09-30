package dev.pti.api.transit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.api.transit.adapter.out.jdbc.JdbcStopDisruptionReader;
import dev.pti.api.transit.adapter.out.jdbc.JdbcStopReader;
import dev.pti.api.transit.adapter.out.jdbc.JdbcStopRoutesReader;
import dev.pti.api.transit.application.port.StopReader;
import dev.pti.api.transit.domain.BoundingBox;
import dev.pti.api.transit.domain.Stop;
import dev.pti.api.transit.domain.StopDisruption;
import dev.pti.api.transit.domain.StopRouteRef;
import dev.pti.api.transit.domain.StopRoutes;
import dev.pti.common.events.Audience;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** E-06 and E-07 against the real warehouse: stop search, the routes of a stop and the open disruptions. */
class StopReadersIT extends TransitIntegrationSupport {

    @Autowired
    private JdbcStopReader stops;

    @Autowired
    private JdbcStopRoutesReader stopRoutes;

    @Autowired
    private JdbcStopDisruptionReader disruptions;

    private static List<String> ids(List<Stop> found) {
        return found.stream().map(Stop::stopId).toList();
    }

    // ------------------------------------------------------------------------------------------------ stops

    @Test
    @DisplayName("E-07 a stop by id with its columns; an entrance and an unknown id are not stops, and are not cached")
    void findStop() {
        installNetwork();

        Stop stop = stops.find(activeFeed(), "s1").orElseThrow();

        assertThat(stop.code()).isEqualTo("1001");
        assertThat(stop.name()).isEqualTo("Nicollet Ave & 46th St");
        assertThat(stop.lat()).isEqualTo(44.9204);
        assertThat(stop.lon()).isEqualTo(-93.2780);
        assertThat(stop.locationType()).isZero();
        assertThat(stop.wheelchairBoarding()).isEqualTo(1);
        assertThat(stops.find(activeFeed(), "st1").orElseThrow().locationType()).isEqualTo(1);
        assertThat(stops.find(activeFeed(), "en1")).isEmpty();
        assertThat(stops.find(activeFeed(), "nope")).isEmpty();
        assertThat(caches.cache("stop-detail").estimatedSize()).isEqualTo(2);
    }

    @Test
    @DisplayName("E-06 q matches the name in any case, ordered by name when they start alike; entrances never appear")
    void textSearch() {
        installNetwork();

        assertThat(ids(stops.searchText(activeFeed(), "nicollet", 20))).containsExactly("s2", "s1", "s3");
        assertThat(ids(stops.searchText(activeFeed(), "NICOLLET AVE", 20))).containsExactly("s2", "s1", "s3");
        assertThat(ids(stops.searchText(activeFeed(), "station", 20))).containsExactly("st1");
        assertThat(ids(stops.searchText(activeFeed(), "entrance", 20))).isEmpty();
        assertThat(ids(stops.searchText(activeFeed(), "nicollet", 2))).hasSize(2);
    }

    @Test
    @DisplayName("EP-07 the exact stop code comes first, before a name that starts with it")
    void codeBeforeName() {
        installNetwork();
        asOwnerInFeed("""
                INSERT INTO dw.dim_stop (feed_version_id, stop_id, stop_code, stop_name, lat, lon)
                VALUES ({fv}, 's9', '9009', '1002 Diner', 44.90, -93.20),
                       ({fv}, 's10', '9010', 'Next to 1002', 44.90, -93.21)""");

        assertThat(ids(stops.searchText(activeFeed(), "1002", 20))).containsExactly("s2", "s9", "s10");
    }

    @Test
    @DisplayName("EP-07 % and _ in q are ordinary characters, and so is a backslash")
    void wildcardsAreEscaped() {
        installNetwork();

        assertThat(ids(stops.searchText(activeFeed(), "%", 20))).containsExactly("s4");
        assertThat(ids(stops.searchText(activeFeed(), "_", 20))).containsExactly("s4");
        assertThat(ids(stops.searchText(activeFeed(), "100%", 20))).containsExactly("s4");
        assertThat(ids(stops.searchText(activeFeed(), "l_stop", 20))).containsExactly("s4");
        assertThat(ids(stops.searchText(activeFeed(), "\\", 20))).isEmpty();
        assertThat(ids(stops.searchText(activeFeed(), "'; DROP TABLE dw.dim_stop; --", 20)))
                .isEmpty();
    }

    @Test
    @DisplayName("E-06 the window: stops inside it, by stop id, with an after cursor and one more row than asked")
    void windowPaging() {
        installNetwork();
        BoundingBox box = new BoundingBox(-93.30, 44.90, -93.25, 45.00);

        List<Stop> first = stops.searchArea(activeFeed(), new StopReader.AreaQuery(box, null, null, null, 3));
        List<Stop> second = stops.searchArea(activeFeed(), new StopReader.AreaQuery(box, null, null, "s3", 10));

        assertThat(ids(first)).containsExactly("s1", "s2", "s3");
        assertThat(ids(second)).containsExactly("s4", "s5", "st1");
        // s6 is at lon -93.21, outside; en1 is an entrance.
    }

    @Test
    @DisplayName("E-06 the route filter: only the stops of the route, and with a window, inside it")
    void routeFilter() {
        installNetwork();
        StopRoutes routes = stopRoutes.read(activeFeed());

        List<Stop> route901 =
                stops.searchArea(activeFeed(), new StopReader.AreaQuery(null, "901", routes.stopsOf("901"), null, 10));
        List<Stop> narrowed = stops.searchArea(
                activeFeed(),
                new StopReader.AreaQuery(
                        new BoundingBox(-93.30, 44.95, -93.20, 45.00), "18", routes.stopsOf("18"), null, 10));

        assertThat(ids(route901)).containsExactly("s6", "st1");
        assertThat(ids(narrowed)).containsExactly("s4", "s5");
    }

    @Test
    @DisplayName("E-06 neither a window nor a route: every stop and station, in stop id order")
    void noFilter() {
        installNetwork();

        assertThat(ids(stops.searchArea(activeFeed(), new StopReader.AreaQuery(null, null, null, null, 100))))
                .containsExactly("s1", "s2", "s3", "s4", "s5", "s6", "st1");
    }

    // ------------------------------------------------------------------------------------------------ stop routes

    @Test
    @DisplayName("E-06, E-07 which routes call at which stops, with their sorted headsigns, built from the stop times")
    void routesOfStops() {
        installNetwork();

        StopRoutes routes = stopRoutes.read(activeFeed());

        assertThat(routes.routeIdsOf("s1")).containsExactly("18", "77");
        assertThat(routes.routesOf("s1").get(0).headsigns()).containsExactly("Airport", "Downtown", "Uptown");
        assertThat(routes.routesOf("s1").get(1)).isEqualTo(new StopRouteRef("77", List.of("Loop")));
        assertThat(routes.routesOf("s5").get(0).headsigns()).containsExactly("Airport", "Downtown");
        assertThat(routes.routeIdsOf("s6")).containsExactly("901");
        assertThat(routes.stopsOf("901")).containsExactlyInAnyOrder("s6", "st1");
        assertThat(routes.stopsOf("77")).containsExactlyInAnyOrder("s1", "s2", "s3");
        assertThat(routes.routeIdsOf("s4")).containsExactly("18");
        assertThat(routes.routeIdsOf("en1")).isEmpty();
        assertThat(caches.cache("stop-routes").estimatedSize()).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------------ disruptions

    private static String alert(
            String id, String type, int severity, String audience, String route, String resolved, String created) {
        return """
                INSERT INTO ops.alert_event (id, type, severity, audience, route_id, ref_table, ref_id, title, body,
                  dedup_key, created_at, resolved_at)
                VALUES ('%s', '%s', %d, '%s', %s, 'insight.insight_service_disruption', 'disruption-%s',
                  'Alert %s', '{"directionId": 0, "episodeStart": "2026-09-29T20:58:00Z"}'::jsonb,
                  'transit-it-%s', TIMESTAMPTZ '%s', %s)""".formatted(
                        id,
                        type,
                        severity,
                        audience,
                        route == null ? "NULL" : "'" + route + "'",
                        id.substring(0, 2),
                        id.substring(0, 2),
                        id,
                        created,
                        resolved == null ? "NULL" : "TIMESTAMPTZ '" + resolved + "'");
    }

    private void alerts() {
        asOwner(
                alert(
                        "a1000000-0000-5000-8000-000000000001",
                        "DISRUPTION",
                        1,
                        "PUBLIC",
                        "18",
                        null,
                        "2026-09-29 21:00:00Z"),
                alert(
                        "a2000000-0000-5000-8000-000000000002",
                        "DISRUPTION",
                        2,
                        "ENGINEERING",
                        "18",
                        null,
                        "2026-09-29 20:00:00Z"),
                alert(
                        "a3000000-0000-5000-8000-000000000003",
                        "DISRUPTION",
                        1,
                        "PUBLIC",
                        "18",
                        null,
                        "2026-09-29 21:30:00Z"),
                alert(
                        "a4000000-0000-5000-8000-000000000004",
                        "DISRUPTION",
                        1,
                        "PUBLIC",
                        "18",
                        "2026-09-29 21:40:00Z",
                        "2026-09-29 21:10:00Z"),
                alert(
                        "a5000000-0000-5000-8000-000000000005",
                        "BUNCHING",
                        1,
                        "PUBLIC",
                        "18",
                        null,
                        "2026-09-29 21:20:00Z"),
                alert(
                        "a6000000-0000-5000-8000-000000000006",
                        "DISRUPTION",
                        1,
                        "PUBLIC",
                        "901",
                        null,
                        "2026-09-29 21:05:00Z"),
                alert(
                        "a7000000-0000-5000-8000-000000000007",
                        "DISRUPTION",
                        0,
                        "OPERATIONS",
                        "18",
                        null,
                        "2026-09-29 22:00:00Z"));
    }

    @Test
    @DisplayName(
            "EP-09 an anonymous view sees the PUBLIC disruptions; the most severe first, then the newest; none resolved")
    void publicDisruptions() {
        installNetwork();
        alerts();

        List<StopDisruption> found = disruptions.findOpen(List.of("18"), Set.of(Audience.PUBLIC));

        assertThat(found)
                .extracting(StopDisruption::alertId)
                .containsExactly("a3000000-0000-5000-8000-000000000003", "a1000000-0000-5000-8000-000000000001");
        StopDisruption newest = found.get(0);
        assertThat(newest.routeId()).isEqualTo("18");
        assertThat(newest.directionId()).isZero();
        assertThat(newest.severity()).isEqualTo(1);
        assertThat(newest.title()).isEqualTo("Alert a3");
        assertThat(newest.disruptionId()).isEqualTo("disruption-a3");
        assertThat(newest.startedAt()).isEqualTo(Instant.parse("2026-09-29T20:58:00Z"));
    }

    @Test
    @DisplayName(
            "EP-09 a viewer sees every audience, and an alert moved from PUBLIC to ENGINEERING leaves the public view")
    void allAudiences() {
        installNetwork();
        alerts();

        assertThat(disruptions.findOpen(List.of("18", "901"), EnumSet.allOf(Audience.class)))
                .extracting(StopDisruption::alertId)
                .containsExactly(
                        "a2000000-0000-5000-8000-000000000002",
                        "a3000000-0000-5000-8000-000000000003",
                        "a6000000-0000-5000-8000-000000000006",
                        "a1000000-0000-5000-8000-000000000001",
                        "a7000000-0000-5000-8000-000000000007");

        asOwner(
                "UPDATE ops.alert_event SET audience = 'ENGINEERING' WHERE id = 'a1000000-0000-5000-8000-000000000001'");
        assertThat(disruptions.findOpen(List.of("18"), Set.of(Audience.PUBLIC)))
                .extracting(StopDisruption::alertId)
                .containsExactly("a3000000-0000-5000-8000-000000000003");
        assertThat(disruptions.findOpen(List.of("18"), EnumSet.allOf(Audience.class)))
                .extracting(StopDisruption::alertId)
                .contains("a1000000-0000-5000-8000-000000000001");
    }

    @Test
    @DisplayName("E-07 at most 20 disruptions, and none for routes without any")
    void limitAndEmpty() {
        installNetwork();
        for (int i = 0; i < 25; i++) {
            asOwner(alert(
                    "b%07d-0000-5000-8000-000000000000".formatted(i),
                    "DISRUPTION",
                    1,
                    "PUBLIC",
                    "18",
                    null,
                    "2026-09-29 21:00:00Z"));
        }

        assertThat(disruptions.findOpen(List.of("18"), Set.of(Audience.PUBLIC))).hasSize(20);
        assertThat(disruptions.findOpen(List.of("77"), Set.of(Audience.PUBLIC))).isEmpty();
    }
}
