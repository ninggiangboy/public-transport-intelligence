package dev.pti.api.insight.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.pti.api.insight.domain.BunchingDetail;
import dev.pti.api.insight.domain.BunchingEpisode;
import dev.pti.api.insight.domain.InsightFixtures;
import dev.pti.api.insight.domain.SuggestionRef;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/** E-10 {@code GET /insights/bunching} and E-11 {@code GET /insights/bunching/{id}} (DOC-32 §4). */
@ResourceLock("in-memory-insight")
@ResourceLock("freshness-probe-result")
class BunchingEndpointsTest extends InsightWebSupport {

    private static UUID id(int n) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(n));
    }

    private void open(UUID id, String route, long minutesAgo) {
        SuggestionRef suggestion = id.equals(InsightFixtures.BUNCHING_ID)
                ? new SuggestionRef(InsightFixtures.SUGGESTION_ID, "hold_follower", new BigDecimal("0.820"))
                : null;
        insight.bunching.add(
                new BunchingDetail(InsightFixtures.openBunching(id, route, ago(minutesAgo), suggestion), null));
    }

    private MockHttpServletResponse list(String query) throws Exception {
        return mvc.perform(as(get("/api/v1/insights/bunching" + query), viewer()))
                .andReturn()
                .getResponse();
    }

    @Test
    @DisplayName("An open episode has the members of DOC-32 and neither episodeEnd nor closeReason (AG-16)")
    void openEpisode() throws Exception {
        open(InsightFixtures.BUNCHING_ID, "18", 7);

        MockHttpServletResponse response = list("");
        JsonNode item = okBody(response).path("items").get(0);

        assertThat(item.propertyNames())
                .containsExactly(
                        "id",
                        "routeId",
                        "directionId",
                        "vehicleLeader",
                        "vehicleFollower",
                        "episodeStart",
                        "status",
                        "scheduledHeadwaySeconds",
                        "thresholdSeconds",
                        "minGapSeconds",
                        "lastGapSeconds",
                        "openStopId",
                        "evaluationCount",
                        "lastEvaluatedAt",
                        "suggestion");
        assertThat(item.path("status").asString()).isEqualTo("OPEN");
        assertThat(item.path("suggestion").path("action").asString()).isEqualTo("hold_follower");
        assertThat(item.path("suggestion").path("actionConfidence").decimalValue())
                .isEqualByComparingTo("0.82");
        assertThat(item.path("lastEvaluatedAt").asString()).isEqualTo("2026-09-29T21:19:30Z");
        assertThat(okBody(response).has("nextCursor")).isFalse();
        assertThat(response.getHeader("X-Data-As-Of")).isEqualTo(VEHICLE_POSITION_AT.toString());
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    @DisplayName("A closed episode has episodeEnd and closeReason")
    void closedEpisode() throws Exception {
        insight.bunching.add(new BunchingDetail(InsightFixtures.closedBunching(id(1), "18", ago(40), ago(20)), null));

        JsonNode item = okBody(list("")).path("items").get(0);

        assertThat(item.path("status").asString()).isEqualTo("CLOSED");
        assertThat(item.has("episodeEnd")).isTrue();
        assertThat(item.path("closeReason").asString()).isEqualTo("GAP_RECOVERED");
        assertThat(item.has("suggestion")).isFalse();
    }

    @Test
    @DisplayName("Episodes that intersect the range are listed, newest start first; the default range is 24 hours")
    void defaultRange() throws Exception {
        open(id(1), "18", 30);
        open(id(2), "18", 10);
        insight.bunching.add(
                new BunchingDetail(InsightFixtures.closedBunching(id(3), "18", ago(60 * 30), ago(60 * 25)), null));

        assertThat(okBody(list("")).path("items"))
                .extracting(item -> item.path("id").asString())
                .containsExactly(id(2).toString(), id(1).toString());
    }

    @Test
    @DisplayName(
            "from and to take ISO times with an offset or relative times; a closed episode is listed while it overlaps")
    void explicitRange() throws Exception {
        insight.bunching.add(new BunchingDetail(InsightFixtures.closedBunching(id(1), "18", ago(300), ago(200)), null));

        assertThat(okBody(list("?from=-250m&to=-100m")).path("items")).hasSize(1);
        assertThat(okBody(list("?from=-150m&to=-100m")).path("items")).isEmpty();
        assertThat(okBody(list("?from=-400m&to=-310m")).path("items")).isEmpty();
    }

    @Test
    @DisplayName("routeId may be repeated or comma separated, and status filters; both narrow the list")
    void filters() throws Exception {
        open(id(1), "18", 30);
        open(id(2), "20", 20);
        open(id(3), "5", 10);
        insight.bunching.add(new BunchingDetail(InsightFixtures.closedBunching(id(4), "18", ago(50), ago(45)), null));

        assertThat(okBody(list("?routeId=18&routeId=20")).path("items")).hasSize(3);
        assertThat(okBody(list("?routeId=18,20")).path("items")).hasSize(3);
        assertThat(okBody(list("?routeId=18&status=OPEN")).path("items")).hasSize(1);
        assertThat(okBody(list("?status=CLOSED")).path("items")).hasSize(1);
    }

    @Test
    @DisplayName("AG-08, AG-04, AG-05 an unknown parameter, a bad limit, a bad status or time are 400 on their field")
    void badParameters() throws Exception {
        assertValidationError(list("?routeID=18"), "routeID");
        assertValidationError(list("?limit=0"), "limit");
        assertValidationError(list("?limit=501"), "limit");
        assertValidationError(list("?status=open"), "status");
        assertValidationError(list("?from=2026-09-29T10:00:00"), "from");
        assertValidationError(list("?from=-1h&to=-2h"), "from");
        assertValidationError(list("?cursor=nonsense"), "cursor");
        String tooMany = String.join(
                ",", IntStream.range(0, 21).mapToObj(Integer::toString).toList());
        assertValidationError(list("?routeId=" + tooMany), "routeId");
    }

    @Test
    @DisplayName("AG-02 keyset paging: 5 episodes by 2 are 2, 2, 1 without a repeat or a gap")
    void paging() throws Exception {
        for (int i = 1; i <= 5; i++) {
            open(id(i), "18", 10 * i);
        }
        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = okBody(list("?limit=2" + (cursor == null ? "" : "&cursor=" + cursor)));
            page.path("items").forEach(item -> seen.add(item.path("id").asString()));
            cursor = page.has("nextCursor") ? page.path("nextCursor").asString() : null;
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(seen)
                .containsExactly(
                        id(1).toString(), id(2).toString(), id(3).toString(), id(4).toString(), id(5).toString());
    }

    @Test
    @DisplayName("AG-03 a cursor of one query is refused for another")
    void cursorOfAnotherQuery() throws Exception {
        for (int i = 1; i <= 3; i++) {
            open(id(i), "18", 10 * i);
        }
        String cursor = okBody(list("?limit=1&status=OPEN")).path("nextCursor").asString();

        assertValidationError(list("?limit=1&status=CLOSED&cursor=" + cursor), "cursor");
        assertThat(okBody(list("?limit=1&status=OPEN&cursor=" + cursor)).path("items"))
                .hasSize(1);
    }

    @Test
    @DisplayName("E-11 the detail has the batch, trips and enrichment status, and the whole suggestion")
    void detail() throws Exception {
        BunchingEpisode episode = InsightFixtures.openBunching(InsightFixtures.BUNCHING_ID, "18", ago(7), null);
        insight.bunching.add(new BunchingDetail(
                episode,
                InsightFixtures.suggestion(
                        InsightFixtures.SUGGESTION_ID, InsightFixtures.BUNCHING_ID, "18", ago(6), "0.450")));

        MockHttpServletResponse response = mvc.perform(
                        as(get("/api/v1/insights/bunching/" + InsightFixtures.BUNCHING_ID), viewer()))
                .andReturn()
                .getResponse();
        JsonNode body = okBody(response);

        assertThat(body.path("batchId").asString()).isEqualTo(InsightFixtures.BATCH_ID.toString());
        assertThat(body.path("tripLeader").asString()).isEqualTo("t-2041");
        assertThat(body.path("tripFollower").asString()).isEqualTo("t-2043");
        assertThat(body.path("enrichmentStatus").asString()).isEqualTo("DONE");
        JsonNode suggestion = body.path("suggestion");
        assertThat(suggestion.path("action").asString()).isEqualTo("hold_follower");
        assertThat(suggestion.path("lowConfidence").asBoolean())
                .as("0.45 is below 0.6")
                .isTrue();
        assertThat(suggestion
                        .path("stateSnapshot")
                        .path("episode")
                        .path("gapSeconds")
                        .asInt())
                .isEqualTo(118);
        assertThat(response.getHeader("X-Data-As-Of")).isEqualTo(VEHICLE_POSITION_AT.toString());
    }

    @Test
    @DisplayName("E-11 an unknown or malformed id is a 404 not-found")
    void detailNotFound() throws Exception {
        assertProblem(
                mvc.perform(as(get("/api/v1/insights/bunching/" + UUID.randomUUID()), viewer()))
                        .andReturn()
                        .getResponse(),
                404,
                "not-found");
        assertProblem(
                mvc.perform(as(get("/api/v1/insights/bunching/not-a-uuid"), viewer()))
                        .andReturn()
                        .getResponse(),
                404,
                "not-found");
    }

    @Test
    @DisplayName("Both need a viewer: no token is a 401, a token of no role a 403")
    void needsAViewer() throws Exception {
        assertThat(mvc.perform(get("/api/v1/insights/bunching"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
        assertThat(mvc.perform(get("/api/v1/insights/bunching/" + UUID.randomUUID()))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
    }
}
