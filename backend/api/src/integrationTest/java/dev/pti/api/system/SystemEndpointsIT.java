package dev.pti.api.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.pti.api.ApiIntegrationSupport;
import dev.pti.api.platform.adapter.out.cache.ApiCaches;
import dev.pti.api.testing.JwtFixture;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * E-60 and E-61 end to end (DOC-32 §10, EP-33, EP-34, O-06): the probe runs every 200 ms against the real database
 * as {@code api_reader}, the endpoint reports what it found, the gauge follows the data and {@code /me} reads a token
 * signed by the tests.
 */
class SystemEndpointsIT extends ApiIntegrationSupport {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper mapper;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private ApiCaches caches;

    @BeforeEach
    @AfterEach
    void clean() {
        asOwner(
                "DELETE FROM dw.gtfs_feed_version",
                "DELETE FROM dw.vehicle_position_latest",
                "DELETE FROM ops.etl_stream_batch",
                "DELETE FROM insight.insight_otp_scorecard",
                "DELETE FROM insight.insight_eta_prediction");
        caches.cache("active-feed").invalidateAll();
    }

    private JsonNode freshness() throws Exception {
        MockHttpServletResponse response =
                mvc.perform(get("/api/v1/system/freshness")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("X-Data-As-Of")).isNotNull();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-cache");
        return mapper.readTree(response.getContentAsString());
    }

    private void awaitProbe(java.util.function.Predicate<JsonNode> condition) {
        await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    MockHttpServletResponse response = mvc.perform(get("/api/v1/system/freshness"))
                            .andReturn()
                            .getResponse();
                    assertThat(response.getStatus()).isEqualTo(200);
                    assertThat(condition.test(mapper.readTree(response.getContentAsString())))
                            .isTrue();
                });
    }

    @Test
    @DisplayName("Without data every source is stale, there is no gauge, and the feed is absent until one is ACTIVE")
    void emptyWarehouse() throws Exception {
        awaitProbe(body -> body.path("stale").asBoolean());
        JsonNode body = freshness();

        assertThat(body.path("stale").asBoolean()).isTrue();
        assertThat(body.has("activeFeed")).isFalse();
        assertThat(body.path("sources")).hasSize(3);
        body.path("sources").forEach(source -> {
            assertThat(source.path("stale").asBoolean()).isTrue();
            assertThat(source.has("lastEventAt")).isFalse();
            assertThat(source.has("ageSeconds")).isFalse();
        });
        assertThat(registry.find("pti.source.last.event.age").gauges()).isEmpty();
    }

    @Test
    @DisplayName("EP-33 fresh data: the sources, the ACTIVE feed, the insight times and the gauge")
    void freshData() throws Exception {
        asOwner(activeFeedSql("a", "2026-08-23"), """
                INSERT INTO dw.vehicle_position_latest (vehicle_id, service_date, route_id, trip_id, direction_id, lat,
                  lon, current_stop_sequence, stop_id, current_status, event_timestamp, batch_id)
                VALUES ('1432', current_date, '18', 't1', 0, 44.97, -93.26, 3, '51405', 'IN_TRANSIT_TO',
                  now() - interval '5 seconds', gen_random_uuid())""", """
                INSERT INTO ops.etl_stream_batch (batch_id, source, listener_id, consumer_group, instance_id, offsets,
                  status, write_mode, records_read, records_written, records_skipped, records_duplicate,
                  max_event_ts, started_at, finished_at)
                VALUES (gen_random_uuid(), 'GTFS_RT_TRIP_UPDATE', 'gtfs-rt-trip-update', 'etl', 'it', '{}',
                  'COMPLETED', 'BATCH', 1, 1, 0, 0, now() - interval '20 seconds', now(), now())""", """
                INSERT INTO insight.insight_eta_prediction (route_id, stop_id, day_of_week, hour_of_day,
                  avg_delay_seconds, median_delay_seconds, p90_delay_seconds, sample_count, window_start, window_end,
                  computed_at, batch_id)
                VALUES ('18', '51405', 1, 8, 60.0, 50, 120, 10, DATE '2026-09-01', DATE '2026-09-28',
                  TIMESTAMPTZ '2026-09-29 21:05:12Z', gen_random_uuid())""", """
                INSERT INTO insight.insight_otp_scorecard (route_id, service_date, otp_percentage, on_time_count,
                  early_count, late_count, observation_count, trip_count, early_tolerance_seconds,
                  late_tolerance_seconds, computed_at, batch_id)
                VALUES ('18', current_date, 87.42, 8, 1, 1, 10, 4, 300, 300, now() - interval '1 hour',
                  gen_random_uuid())""");

        awaitProbe(body -> body.path("sources").get(0).has("lastEventAt")
                && body.path("sources").get(1).has("lastEventAt")
                && body.has("activeFeed")
                && body.path("insights").has("etaComputedAt"));
        JsonNode body = freshness();

        assertThat(body.path("stale").asBoolean()).isFalse();
        assertThat(body.path("activeFeed").path("timezone").asString()).isEqualTo("America/Chicago");
        assertThat(body.path("activeFeed").path("publisherFeedVersion").asString())
                .isEqualTo("2026-08-23");
        assertThat(body.path("sources").get(0).path("source").asString()).isEqualTo("GTFS_RT_VEHICLE_POSITION");
        assertThat(body.path("sources").get(0).path("ageSeconds").asLong()).isBetween(0L, 60L);
        assertThat(body.path("sources").get(1).path("ageSeconds").asLong()).isBetween(15L, 75L);
        assertThat(body.path("sources").get(2).path("source").asString()).isEqualTo("TICKETING_SALES");
        assertThat(body.path("sources").get(2).has("lastEventAt")).isFalse();
        assertThat(body.path("insights").path("etaComputedAt").asString()).isEqualTo("2026-09-29T21:05:12Z");
        assertThat(body.path("insights").has("otpComputedAt")).isTrue();
        assertThat(registry.find("pti.source.last.event.age")
                        .tag("source", "GTFS_RT_VEHICLE_POSITION")
                        .gauge())
                .isNotNull();
        assertThat(registry.find("pti.source.last.event.age")
                        .tag("source", "TICKETING_SALES")
                        .gauge())
                .isNull();
    }

    @Test
    @DisplayName("O-06 the gauge is the age of the newest event and appears once a source has data")
    void gaugeFollowsTheData() throws Exception {
        asOwner("""
                INSERT INTO dw.vehicle_position_latest (vehicle_id, service_date, route_id, trip_id, direction_id, lat,
                  lon, current_stop_sequence, stop_id, current_status, event_timestamp, batch_id)
                VALUES ('1437', current_date, '18', 't2', 0, 44.97, -93.26, 3, '51405', 'IN_TRANSIT_TO',
                  now() - interval '30 seconds', gen_random_uuid())""");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            var gauge = registry.find("pti.source.last.event.age")
                    .tag("source", "GTFS_RT_VEHICLE_POSITION")
                    .gauge();
            assertThat(gauge).isNotNull();
            assertThat(gauge.value()).isBetween(29.0, 90.0);
        });
    }

    @Test
    @DisplayName("EP-34 /me reads the token: anonymous, viewer and operator")
    void me() throws Exception {
        JsonNode anonymous = mapper.readTree(
                mvc.perform(get("/api/v1/me")).andReturn().getResponse().getContentAsString());
        JsonNode viewer = mapper.readTree(
                mvc.perform(get("/api/v1/me").header("Authorization", JwtFixture.bearer(JwtFixture.viewer())))
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
        JsonNode operator = mapper.readTree(
                mvc.perform(get("/api/v1/me").header("Authorization", JwtFixture.bearer(JwtFixture.operator())))
                        .andReturn()
                        .getResponse()
                        .getContentAsString());

        assertThat(anonymous.path("authenticated").asBoolean()).isFalse();
        assertThat(anonymous.path("roles")).isEmpty();
        assertThat(viewer.path("username").asString()).isEqualTo("viewer");
        assertThat(viewer.path("roles")).hasSize(1);
        assertThat(operator.path("username").asString()).isEqualTo("operator");
        assertThat(operator.path("roles").get(0).asString()).isEqualTo("operator");
        assertThat(operator.path("roles").get(1).asString()).isEqualTo("viewer");
    }
}
