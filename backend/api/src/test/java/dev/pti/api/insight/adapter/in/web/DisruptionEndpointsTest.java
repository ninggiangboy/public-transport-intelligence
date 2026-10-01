package dev.pti.api.insight.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.pti.api.insight.domain.InsightFixtures;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/** E-12 {@code GET /insights/disruption} and E-13 {@code GET /insights/disruption/{id}} (DOC-32 §4, EP-11, EP-12). */
@ResourceLock("in-memory-insight")
@ResourceLock("freshness-probe-result")
class DisruptionEndpointsTest extends InsightWebSupport {

    private static final UUID PUBLIC_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID HIDDEN_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @BeforeEach
    void data() {
        insight.disruptions.add(InsightFixtures.disruption(PUBLIC_ID, "18", ago(20), "PUBLIC", true));
        insight.disruptions.add(InsightFixtures.disruption(HIDDEN_ID, "18", ago(30), "ENGINEERING", true));
    }

    private MockHttpServletResponse call(MockHttpServletRequestBuilder request, String bearer) throws Exception {
        return mvc.perform(bearer == null ? request : as(request, bearer))
                .andReturn()
                .getResponse();
    }

    @Test
    @DisplayName("EP-11 an anonymous caller lists the public episodes only; a viewer lists them all")
    void listByCaller() throws Exception {
        JsonNode anonymous = okBody(call(get("/api/v1/insights/disruption"), null));
        JsonNode viewer = okBody(call(get("/api/v1/insights/disruption"), viewer()));

        assertThat(anonymous.path("items"))
                .extracting(item -> item.path("id").asString())
                .containsExactly(PUBLIC_ID.toString());
        assertThat(viewer.path("items"))
                .extracting(item -> item.path("id").asString())
                .containsExactly(PUBLIC_ID.toString(), HIDDEN_ID.toString());
    }

    @Test
    @DisplayName("EP-12 the public view has only the allowed members: no AI field, baseline, z-score or audience")
    void publicView() throws Exception {
        JsonNode item = okBody(call(get("/api/v1/insights/disruption"), null))
                .path("items")
                .get(0);

        assertThat(item.propertyNames())
                .containsExactly(
                        "id",
                        "routeId",
                        "directionId",
                        "episodeStart",
                        "status",
                        "severity",
                        "currentAvgDelaySeconds",
                        "peakAvgDelaySeconds",
                        "affectedStopIds");
        assertThat(item.path("affectedStopIds")).hasSize(3);
        assertThat(item.path("currentAvgDelaySeconds").decimalValue()).isEqualByComparingTo("212.7");
    }

    @Test
    @DisplayName("The full view, for a viewer, has the baseline, z-scores, audience and the AI fields")
    void fullView() throws Exception {
        JsonNode item = okBody(call(get("/api/v1/insights/disruption"), viewer()))
                .path("items")
                .get(0);

        assertThat(item.propertyNames())
                .containsExactly(
                        "id",
                        "routeId",
                        "directionId",
                        "episodeStart",
                        "status",
                        "severity",
                        "audience",
                        "baselineMeanSeconds",
                        "baselineStddevSeconds",
                        "currentAvgDelaySeconds",
                        "currentZScore",
                        "peakAvgDelaySeconds",
                        "peakZScore",
                        "sampleCount",
                        "affectedStopIds",
                        "lastBucket",
                        "enrichmentStatus",
                        "dataIssueProbability",
                        "likelyCause",
                        "causeConfidence",
                        "modelVersion");
        assertThat(item.path("audience").asString()).isEqualTo("PUBLIC");
        assertThat(item.path("likelyCause").asString()).isEqualTo("traffic");
        assertThat(item.path("currentZScore").decimalValue()).isEqualByComparingTo("3.98");
    }

    @Test
    @DisplayName("The answer says it varies by Authorization; the public one is no-cache, the viewer's is no-store")
    void headers() throws Exception {
        MockHttpServletResponse anonymous = call(get("/api/v1/insights/disruption"), null);
        MockHttpServletResponse viewer = call(get("/api/v1/insights/disruption"), viewer());

        assertThat(anonymous.getHeader("Vary")).isEqualTo("Authorization");
        assertThat(viewer.getHeader("Vary")).isEqualTo("Authorization");
        assertThat(anonymous.getHeader("Cache-Control")).isEqualTo("no-cache");
        assertThat(viewer.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(anonymous.getHeader("X-Data-As-Of")).isEqualTo(TRIP_UPDATE_AT.toString());
    }

    @Test
    @DisplayName("Filters and paging work as for bunching: routeId, status, limit and cursor")
    void filtersAndPaging() throws Exception {
        assertThat(okBody(call(get("/api/v1/insights/disruption?routeId=20"), viewer()))
                        .path("items"))
                .isEmpty();
        assertThat(okBody(call(get("/api/v1/insights/disruption?status=CLOSED"), viewer()))
                        .path("items"))
                .isEmpty();
        JsonNode first = okBody(call(get("/api/v1/insights/disruption?limit=1"), viewer()));
        assertThat(first.path("items")).hasSize(1);
        JsonNode second = okBody(call(
                get("/api/v1/insights/disruption?limit=1&cursor="
                        + first.path("nextCursor").asString()),
                viewer()));
        assertThat(second.path("items").get(0).path("id").asString()).isEqualTo(HIDDEN_ID.toString());
        assertThat(second.has("nextCursor")).isFalse();
        assertValidationError(call(get("/api/v1/insights/disruption?status=DONE"), null), "status");
        assertValidationError(call(get("/api/v1/insights/disruption?routeID=18"), null), "routeID");
    }

    @Test
    @DisplayName(
            "E-13 the detail of a public episode: anonymous gets the public view, a viewer the full one with the extras")
    void detail() throws Exception {
        JsonNode anonymous = okBody(call(get("/api/v1/insights/disruption/" + PUBLIC_ID), null));
        JsonNode viewer = okBody(call(get("/api/v1/insights/disruption/" + PUBLIC_ID), viewer()));

        assertThat(anonymous.has("audience")).isFalse();
        assertThat(anonymous.has("batchId")).isFalse();
        assertThat(viewer.path("closeReason").asString()).isEqualTo("RECOVERED");
        assertThat(viewer.path("batchId").asString()).isEqualTo(InsightFixtures.BATCH_ID.toString());
        assertThat(viewer.path("enrichedAt").asString()).isEqualTo("2026-09-29T21:05:00Z");
    }

    @Test
    @DisplayName("EP-11 the detail of an episode that is not public is a 404 for anonymous, and there for a viewer")
    void detailOfAHiddenEpisode() throws Exception {
        assertProblem(call(get("/api/v1/insights/disruption/" + HIDDEN_ID), null), 404, "not-found");
        assertThat(okBody(call(get("/api/v1/insights/disruption/" + HIDDEN_ID), viewer()))
                        .path("audience")
                        .asString())
                .isEqualTo("ENGINEERING");
        assertProblem(call(get("/api/v1/insights/disruption/" + UUID.randomUUID()), viewer()), 404, "not-found");
        assertProblem(call(get("/api/v1/insights/disruption/nope"), null), 404, "not-found");
    }
}
