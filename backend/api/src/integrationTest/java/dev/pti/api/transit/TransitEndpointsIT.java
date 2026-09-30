package dev.pti.api.transit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.pti.api.testing.JwtFixture;
import dev.pti.common.gtfs.GtfsTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * E-01 to E-08 end to end: HTTP, use cases, JDBC readers as {@code api_reader}, the real warehouse. The data is the
 * network of {@link TransitIntegrationSupport}; what depends on the clock (vehicles, arrivals) is placed relative to
 * the real time, as the business clock has no offset here.
 */
class TransitEndpointsIT extends TransitIntegrationSupport {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper mapper;

    private MockHttpServletResponse call(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse();
    }

    private JsonNode json(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return mapper.readTree(response.getContentAsString());
    }

    private static String viewer() {
        return JwtFixture.bearer(JwtFixture.viewer());
    }

    // ------------------------------------------------------------------------------------------------ E-01, E-02

    @Test
    @DisplayName("E-01, E-02 routes and the pattern of a route, with the headers of DOC-31")
    void routes() throws Exception {
        installNetwork();

        MockHttpServletResponse list = call(get("/api/v1/routes"));
        JsonNode body = json(list);
        assertThat(body.path("feedVersionId").asLong()).isEqualTo(feedVersionId);
        assertThat(body.path("items"))
                .extracting(item -> item.path("routeId").asString())
                .containsExactly("901", "18", "77");
        assertThat(list.getHeader("Cache-Control")).isEqualTo("max-age=60, public");
        assertThat(list.getHeader("X-Data-As-Of")).isEqualTo("2026-09-27T08:34:40Z");
        assertThat(list.getHeader("X-Trace-Id")).hasSize(32);
        assertThat(json(call(get("/api/v1/routes").param("routeType", "0"))).path("items"))
                .hasSize(1);

        JsonNode route = json(call(get("/api/v1/routes/18")));
        assertThat(route.path("directions")).hasSize(2);
        JsonNode north = route.path("directions").get(0);
        assertThat(north.path("shapeId").asString()).isEqualTo("shB");
        assertThat(north.path("geometrySource").asString()).isEqualTo("SHAPE");
        assertThat(north.path("geometry").path("type").asString()).isEqualTo("LineString");
        assertThat(north.path("geometry").path("coordinates")).hasSize(3);
        assertThat(north.path("geometry").path("coordinates").get(0).get(0).asDouble())
                .isEqualTo(-93.278);
        assertThat(north.path("stops")).hasSize(5);
        assertThat(json(call(get("/api/v1/routes/77")))
                        .path("directions")
                        .get(0)
                        .path("geometrySource")
                        .asString())
                .isEqualTo("STOPS");
        assertThat(call(get("/api/v1/routes/nope")).getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("AG-18 when the ACTIVE feed changes, /routes follows and the caches of the old feed are dropped")
    void feedChange() throws Exception {
        installNetwork();
        long first = json(call(get("/api/v1/routes"))).path("feedVersionId").asLong();
        json(call(get("/api/v1/routes/18")));
        assertThat(caches.cache("route-detail").estimatedSize()).isEqualTo(1);

        asOwner(
                "UPDATE dw.gtfs_feed_version SET status = 'RETIRED', retired_at = now() WHERE status = 'ACTIVE'",
                activeFeedSql("c", "2026-09-30"),
                """
                INSERT INTO dw.dim_agency (feed_version_id, agency_id, agency_name, agency_timezone)
                SELECT feed_version_id, '0', 'Metro Transit', 'America/Chicago'
                FROM dw.gtfs_feed_version WHERE status = 'ACTIVE'""",
                """
                INSERT INTO dw.dim_route (feed_version_id, route_id, agency_id, display_name, route_type)
                SELECT feed_version_id, '5', '0', '5', 3 FROM dw.gtfs_feed_version WHERE status = 'ACTIVE'""");

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            JsonNode body = json(call(get("/api/v1/routes")));
            assertThat(body.path("feedVersionId").asLong()).isGreaterThan(first);
            assertThat(body.path("items")).hasSize(1);
        });
        assertThat(caches.cache("route-detail").estimatedSize()).isZero();
        assertThat(call(get("/api/v1/routes/18")).getStatus()).isEqualTo(404);
    }

    // ------------------------------------------------------------------------------------------------ E-03, E-04

    @Test
    @DisplayName("E-03 needs a viewer; the delays of a range come bucketed, with the tolerance")
    void delays() throws Exception {
        installNetwork();
        partitions("2026-09-28", "2026-09-30");
        asOwner(
                tripUpdate("2026-09-29", "u1", 1, "2026-09-29 21:10:00+00", 60),
                tripUpdate("2026-09-29", "u2", 1, "2026-09-29 21:40:00+00", 600));

        assertThat(call(get("/api/v1/routes/18/delays")).getStatus()).isEqualTo(401);
        MockHttpServletResponse response = call(get("/api/v1/routes/18/delays")
                .param("from", "2026-09-28T00:00:00Z")
                .param("to", "2026-09-30T00:00:00Z")
                .header("Authorization", viewer()));
        JsonNode body = json(response);

        assertThat(body.path("bucket").asString()).isEqualTo("hour");
        assertThat(body.path("earlyToleranceSeconds").asInt()).isEqualTo(300);
        assertThat(body.path("items")).hasSize(1);
        assertThat(body.path("items").get(0).path("bucketStart").asString()).isEqualTo("2026-09-29T21:00:00Z");
        assertThat(body.path("items").get(0).path("avgDelaySeconds").asDouble()).isEqualTo(330.0);
        assertThat(body.path("items").get(0).path("observationCount").asInt()).isEqualTo(2);
        assertThat(body.path("items").get(0).path("onTimePercentage").asDouble())
                .isEqualTo(50.0);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("max-age=60, private");
        assertThat(call(get("/api/v1/routes/nope/delays").header("Authorization", viewer()))
                        .getStatus())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("E-04 the profile lists the stops of the direction in order with the history that exists")
    void delayProfile() throws Exception {
        installNetwork();
        asOwner("""
                INSERT INTO insight.insight_eta_prediction (route_id, stop_id, day_of_week, hour_of_day,
                  avg_delay_seconds, median_delay_seconds, p90_delay_seconds, sample_count, window_start, window_end,
                  computed_at, batch_id)
                VALUES ('18', 's2', 2, 16, 64.2, 51, 170, 36, DATE '2026-09-01', DATE '2026-09-28',
                  TIMESTAMPTZ '2026-09-29 21:05:12Z', gen_random_uuid())""");

        JsonNode body = json(call(get("/api/v1/routes/18/delay-profile")
                .param("directionId", "0")
                .param("dayOfWeek", "2")
                .param("hourOfDay", "16")));

        assertThat(body.path("items")).hasSize(5);
        assertThat(body.path("items").get(0).path("confidence").asString()).isEqualTo("NONE");
        assertThat(body.path("items").get(1).path("stopId").asString()).isEqualTo("s2");
        assertThat(body.path("items").get(1).path("avgDelaySeconds").asDouble()).isEqualTo(64.2);
        assertThat(body.path("items").get(1).path("confidence").asString()).isEqualTo("HIGH");
        assertThat(body.path("computedAt").asString()).isEqualTo("2026-09-29T21:05:12Z");
        assertThat(call(get("/api/v1/routes/18/delay-profile").param("directionId", "1"))
                        .getStatus())
                .isEqualTo(200);
        assertThat(call(get("/api/v1/routes/18/delay-profile").param("directionId", "2"))
                        .getStatus())
                .isEqualTo(400);
    }

    // ------------------------------------------------------------------------------------------------ E-05

    @Test
    @DisplayName("E-05, EP-05, EP-06 live vehicles of the last 5 minutes; the bunching overlay is for viewers")
    void liveVehicles() throws Exception {
        installNetwork();
        partitions(
                LocalDate.now().minusDays(1).toString(),
                LocalDate.now().plusDays(1).toString());
        asOwner("""
                INSERT INTO dw.vehicle_position_latest (vehicle_id, service_date, route_id, trip_id, direction_id, lat,
                  lon, current_stop_sequence, stop_id, current_status, event_timestamp, batch_id)
                VALUES
                  ('1187', current_date, '18', 'b3', 0, 44.93, -93.278, 1, 's1', 'STOPPED_AT', now() - interval '5 seconds', gen_random_uuid()),
                  ('1203', current_date, '18', 'b2', 0, 44.94, -93.278, 2, 's2', 'IN_TRANSIT_TO', now() - interval '10 seconds', gen_random_uuid()),
                  ('9999', current_date, '18', 'b1', 0, 44.92, -93.27, 1, 's1', 'STOPPED_AT', now() - interval '6 minutes', gen_random_uuid())""", """
                INSERT INTO insight.insight_bus_bunching (id, route_id, direction_id, vehicle_leader, vehicle_follower,
                  trip_leader, trip_follower, episode_start, status, scheduled_headway_seconds, threshold_seconds,
                  min_gap_seconds, last_gap_seconds, last_evaluated_at, batch_id)
                VALUES ('6f1c2a9e-4b1d-5c8e-9a2f-3d4e5f6a7b8c', '18', 0, '1187', '1203', 'b3', 'b2', now(), 'OPEN', 600,
                  300, 100, 112, now(), gen_random_uuid())""");

        JsonNode anonymous = json(call(get("/api/v1/vehicles/live")));
        assertThat(anonymous.path("count").asInt()).isEqualTo(2);
        assertThat(anonymous.path("items"))
                .extracting(item -> item.path("vehicleId").asString())
                .containsExactly("1187", "1203");
        assertThat(anonymous.path("items")).noneMatch(item -> item.has("bunching"));

        caches.cache("vehicles-live").invalidateAll();
        JsonNode signedIn = json(call(get("/api/v1/vehicles/live").header("Authorization", viewer())));
        assertThat(signedIn.path("items").get(0).path("bunching").path("role").asString())
                .isEqualTo("LEADER");
        assertThat(signedIn.path("items").get(1).path("bunching").path("role").asString())
                .isEqualTo("FOLLOWER");
        assertThat(signedIn.path("items")
                        .get(1)
                        .path("bunching")
                        .path("partnerVehicleId")
                        .asString())
                .isEqualTo("1187");
    }

    // ------------------------------------------------------------------------------------------------ E-06, E-07

    @Test
    @DisplayName("E-06 EP-07 EP-08 the text search with an escaped wildcard, the window and its limit")
    void stopSearch() throws Exception {
        installNetwork();

        JsonNode byName = json(call(get("/api/v1/stops").param("q", "nicollet")));
        assertThat(byName.path("items"))
                .extracting(item -> item.path("stopId").asString())
                .containsExactly("s2", "s1", "s3");
        assertThat(byName.path("items").get(1).path("routeIds"))
                .extracting(JsonNode::asString)
                .containsExactly("18", "77");
        assertThat(byName.has("nextCursor")).isFalse();
        assertThat(json(call(get("/api/v1/stops").param("q", "0%"))).path("items"))
                .hasSize(1);
        assertThat(call(get("/api/v1/stops").param("bbox", "-94.0,44.0,-93.0,45.0"))
                        .getStatus())
                .isEqualTo(400);

        JsonNode page = json(call(get("/api/v1/stops").param("routeId", "18").param("limit", "2")));
        assertThat(page.path("items")).hasSize(2);
        String cursor = page.path("nextCursor").asString();
        JsonNode next = json(call(
                get("/api/v1/stops").param("routeId", "18").param("limit", "10").param("cursor", cursor)));
        assertThat(next.path("items"))
                .extracting(item -> item.path("stopId").asString())
                .containsExactly("s3", "s4", "s5");
        assertThat(next.has("nextCursor")).isFalse();
    }

    @Test
    @DisplayName(
            "E-07 EP-09 the stop with its routes; a PUBLIC disruption is seen by everyone until it is made internal")
    void stopDetail() throws Exception {
        installNetwork();
        asOwner("""
                INSERT INTO ops.alert_event (id, type, severity, audience, route_id, ref_table, ref_id, title, body,
                  dedup_key, created_at)
                VALUES ('a1000000-0000-5000-8000-000000000001', 'DISRUPTION', 1, 'PUBLIC', '18',
                  'insight.insight_service_disruption', '9d8e7f6a-5b4c-5d3e-8f2a-1b0c9d8e7f6a',
                  'Delays on route 18 northbound', '{"directionId": 0, "episodeStart": "2026-09-29T20:58:00Z"}'::jsonb,
                  'transit-it-e07', now())""");

        JsonNode anonymous = json(call(get("/api/v1/stops/s1")));
        assertThat(anonymous.path("routes"))
                .extracting(route -> route.path("routeId").asString())
                .containsExactly("18", "77");
        assertThat(anonymous.path("routes").get(0).path("headsigns"))
                .extracting(JsonNode::asString)
                .containsExactly("Airport", "Downtown", "Uptown");
        assertThat(anonymous.path("routes").get(0).path("color").asString()).isEqualTo("771473");
        assertThat(anonymous.path("activeDisruptions")).hasSize(1);
        assertThat(anonymous.path("activeDisruptions").get(0).path("startedAt").asString())
                .isEqualTo("2026-09-29T20:58:00Z");

        asOwner("UPDATE ops.alert_event SET audience = 'ENGINEERING' WHERE dedup_key = 'transit-it-e07'");
        assertThat(json(call(get("/api/v1/stops/s1"))).path("activeDisruptions"))
                .isEmpty();
        assertThat(json(call(get("/api/v1/stops/s1").header("Authorization", viewer())))
                        .path("activeDisruptions"))
                .hasSize(1);
        assertThat(call(get("/api/v1/stops/en1")).getStatus()).isEqualTo(404);
    }

    // ------------------------------------------------------------------------------------------------ E-08

    @Test
    @DisplayName("E-08 a trip that calls at the stop ten minutes from now is listed with its scheduled time")
    void arrivals() throws Exception {
        installNetwork();
        Instant target = Instant.now().plus(Duration.ofMinutes(10)).truncatedTo(ChronoUnit.SECONDS);
        LocalDate serviceDate = target.atZone(CHICAGO).toLocalDate();
        int seconds = (int) Duration.between(GtfsTime.toInstant(serviceDate, 0, CHICAGO), target)
                .toSeconds();
        asOwnerInFeed(
                "UPDATE dw.gtfs_calendar SET start_date = current_date - 30, end_date = current_date + 30",
                """
                INSERT INTO dw.gtfs_trip (feed_version_id, trip_id, route_id, service_id, direction_id, trip_headsign)
                VALUES ({fv}, 'live1', '18', 'wk', 0, 'Downtown Minneapolis')""",
                "INSERT INTO dw.gtfs_stop_time (feed_version_id, trip_id, stop_sequence, stop_id, arrival_seconds, departure_seconds)"
                        + " VALUES ({fv}, 'live1', 1, 's2', " + seconds + ", " + seconds + ")");

        MockHttpServletResponse response = call(get("/api/v1/stops/s2/arrivals").param("limit", "5"));
        JsonNode body = json(response);

        assertThat(body.path("stopId").asString()).isEqualTo("s2");
        assertThat(body.path("realtimeEnabled").asBoolean()).isFalse();
        JsonNode item = body.path("items").get(0);
        assertThat(item.path("tripId").asString()).isEqualTo("live1");
        assertThat(item.path("scheduledArrival").asString()).isEqualTo(target.toString());
        assertThat(item.path("predictedArrival").asString()).isEqualTo(target.toString());
        assertThat(item.path("confidence").asString()).isEqualTo("NONE");
        assertThat(item.path("serviceDate").asString()).isEqualTo(serviceDate.toString());
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-cache");
        assertThat(call(get("/api/v1/stops/nope/arrivals")).getStatus()).isEqualTo(404);
        assertThat(call(get("/api/v1/stops/s2/arrivals").param("horizon", "PT5M"))
                        .getStatus())
                .isEqualTo(400);
    }
}
