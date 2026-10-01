package dev.pti.api.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.pti.api.testing.JwtFixture;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * E-10 to E-18 end to end: HTTP, use cases, the SQL of {@code sql/insight/} as {@code api_reader} and {@code
 * replay_operator}, the real warehouse with its real migrations (DOC-32 §4, §13).
 */
class InsightEndpointsIT extends InsightIntegrationSupport {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper mapper;

    private static String viewer() {
        return JwtFixture.bearer(JwtFixture.viewer());
    }

    private static String operator() {
        return JwtFixture.bearer(JwtFixture.operator());
    }

    private MockHttpServletResponse call(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse();
    }

    private JsonNode json(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return mapper.readTree(response.getContentAsString());
    }

    private List<String> ids(JsonNode body) {
        return body.path("items")
                .valueStream()
                .map(i -> i.path("id").asString())
                .toList();
    }

    private static String minutesAgo(int minutes) {
        return Instant.now().minusSeconds(minutes * 60L).toString();
    }

    // ------------------------------------------------------------------------------------------------ E-10, E-11

    @Test
    @DisplayName("E-10 bunching: filters, the short suggestion, open and closed episodes, X-Trace-Id")
    void bunchingList() throws Exception {
        insertBunching(BUNCHING, "18", minutesAgo(30), null, "OPEN");
        insertSuggestion(SUGGESTION, BUNCHING, "18", minutesAgo(29), "0.820");
        insertBunching("00000000-0000-0000-0000-000000000002", "20", minutesAgo(90), minutesAgo(60), "CLOSED");
        insertBunching(
                "00000000-0000-0000-0000-000000000003", "18", minutesAgo(60 * 40), minutesAgo(60 * 39), "CLOSED");

        MockHttpServletResponse response = call(get("/api/v1/insights/bunching").header("Authorization", viewer()));
        JsonNode body = json(response);

        assertThat(ids(body)).containsExactly(BUNCHING, "00000000-0000-0000-0000-000000000002");
        JsonNode open = body.path("items").get(0);
        assertThat(open.has("episodeEnd")).isFalse();
        assertThat(open.path("suggestion").path("id").asString()).isEqualTo(SUGGESTION);
        assertThat(open.path("suggestion").path("actionConfidence").decimalValue())
                .isEqualByComparingTo("0.82");
        assertThat(body.path("items").get(1).path("closeReason").asString()).isEqualTo("GAP_RECOVERED");
        assertThat(response.getHeader("X-Trace-Id")).hasSize(32);

        assertThat(ids(json(call(get("/api/v1/insights/bunching?routeId=20,5").header("Authorization", viewer())))))
                .containsExactly("00000000-0000-0000-0000-000000000002");
        assertThat(ids(json(call(get("/api/v1/insights/bunching?status=OPEN").header("Authorization", viewer())))))
                .containsExactly(BUNCHING);
        assertThat(ids(json(call(
                        get("/api/v1/insights/bunching?from=-3000h&to=-2300h").header("Authorization", viewer())))))
                .isEmpty();
    }

    @Test
    @DisplayName("E-10 AG-02 keyset paging over microsecond timestamps: no row twice, none missed, equal starts by id")
    void bunchingPaging() throws Exception {
        String sameStart = "2026-09-29T21:00:00.123456Z";
        for (int i = 1; i <= 5; i++) {
            insertBunching(
                    "00000000-0000-0000-0000-%012d".formatted(i),
                    "18",
                    i <= 3 ? sameStart : "2026-09-29T20:00:00." + i + "00001Z",
                    "2026-09-29T21:30:00Z",
                    "CLOSED");
        }
        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page =
                    json(call(get("/api/v1/insights/bunching?from=2026-09-29T00:00:00Z&to=2026-09-30T00:00:00Z&limit=2"
                                    + (cursor == null ? "" : "&cursor=" + cursor))
                            .header("Authorization", viewer())));
            seen.addAll(ids(page));
            cursor = page.has("nextCursor") ? page.path("nextCursor").asString() : null;
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(seen)
                .containsExactly(
                        "00000000-0000-0000-0000-000000000003",
                        "00000000-0000-0000-0000-000000000002",
                        "00000000-0000-0000-0000-000000000001",
                        "00000000-0000-0000-0000-000000000005",
                        "00000000-0000-0000-0000-000000000004");
    }

    @Test
    @DisplayName("E-11 bunching detail: the batch, the trips and the whole suggestion; 404 for an unknown id")
    void bunchingDetail() throws Exception {
        insertBunching(BUNCHING, "18", minutesAgo(30), null, "OPEN");
        insertSuggestion(SUGGESTION, BUNCHING, "18", minutesAgo(29), "0.450");

        JsonNode body = json(call(get("/api/v1/insights/bunching/" + BUNCHING).header("Authorization", viewer())));

        assertThat(body.path("tripLeader").asString()).isEqualTo("t-2041");
        assertThat(body.path("batchId").asString()).isEqualTo(BATCH);
        assertThat(body.path("suggestion").path("lowConfidence").asBoolean()).isTrue();
        assertThat(body.path("suggestion").path("stateSnapshot").path("task").asString())
                .isEqualTo("Suggest one dispatch action.");
        assertThat(call(get("/api/v1/insights/bunching/00000000-0000-0000-0000-0000000000ff")
                                .header("Authorization", viewer()))
                        .getStatus())
                .isEqualTo(404);
    }

    // ------------------------------------------------------------------------------------------------ E-12, E-13

    @Test
    @DisplayName("EP-11, EP-12 disruption: anonymous sees the public episodes in the public view, a viewer all of them")
    void disruption() throws Exception {
        insertDisruption(PUBLIC_DISRUPTION, "18", minutesAgo(20), "PUBLIC");
        insertDisruption(HIDDEN_DISRUPTION, "18", minutesAgo(30), "ENGINEERING");

        JsonNode anonymous = json(call(get("/api/v1/insights/disruption")));
        JsonNode viewer = json(call(get("/api/v1/insights/disruption").header("Authorization", viewer())));

        assertThat(ids(anonymous)).containsExactly(PUBLIC_DISRUPTION);
        assertThat(anonymous.path("items").get(0).has("likelyCause")).isFalse();
        assertThat(anonymous.path("items").get(0).path("affectedStopIds")).hasSize(2);
        assertThat(ids(viewer)).containsExactly(PUBLIC_DISRUPTION, HIDDEN_DISRUPTION);
        JsonNode full = viewer.path("items").get(0);
        assertThat(full.path("audience").asString()).isEqualTo("PUBLIC");
        assertThat(full.path("likelyCause").asString()).isEqualTo("traffic");
        assertThat(full.path("currentZScore").decimalValue()).isEqualByComparingTo("3.98");

        assertThat(call(get("/api/v1/insights/disruption/" + HIDDEN_DISRUPTION)).getStatus())
                .isEqualTo(404);
        JsonNode detail = json(
                call(get("/api/v1/insights/disruption/" + HIDDEN_DISRUPTION).header("Authorization", viewer())));
        assertThat(detail.path("batchId").asString()).isEqualTo(BATCH);
        assertThat(detail.has("enrichedAt")).isTrue();
    }

    @Test
    @DisplayName("DOC-31 §10.3 the public list is kept in the public-disruptions cache; a viewer's list never is")
    void publicDisruptionsAreCached() throws Exception {
        insertDisruption(PUBLIC_DISRUPTION, "18", minutesAgo(20), "PUBLIC");
        // Absolute times, so that every request has the same key.
        String url = "/api/v1/insights/disruption?from=" + minutesAgo(120) + "&to=" + minutesAgo(-60);
        assertThat(ids(json(call(get(url))))).containsExactly(PUBLIC_DISRUPTION);

        asOwner("DELETE FROM insight.insight_service_disruption", "DELETE FROM ops.alert_event");

        assertThat(ids(json(call(get(url))))).as("served from the cache").containsExactly(PUBLIC_DISRUPTION);
        assertThat(ids(json(call(get(url).header("Authorization", viewer())))))
                .as("a viewer reads the database")
                .isEmpty();
        caches.cache("public-disruptions").invalidateAll();
        assertThat(ids(json(call(get(url))))).isEmpty();
    }

    // ------------------------------------------------------------------------------------------------ E-14

    @Test
    @DisplayName(
            "EP-13 OTP: counters are summed, tolerances that differ are mixed, route types filter through the feed")
    void otp() throws Exception {
        installFeedWithRoutes();
        insertOtp("18", "2026-09-22", 90, 5, 5, 300);
        insertOtp("18", "2026-09-23", 10, 45, 45, 240);
        insertOtp("901", "2026-09-22", 95, 2, 3, 300);

        JsonNode body = json(call(get("/api/v1/insights/otp?fromDate=2026-09-22&toDate=2026-09-28")
                .header("Authorization", viewer())));

        assertThat(body.path("items"))
                .extracting(i -> i.path("routeId").asString())
                .containsExactly("18", "901");
        JsonNode route18 = body.path("items").get(0);
        assertThat(route18.path("otpPercentage").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(route18.path("mixedTolerances").asBoolean()).isTrue();
        assertThat(route18.has("earlyToleranceSeconds")).isFalse();
        assertThat(route18.path("daily")).hasSize(2);
        assertThat(body.path("items").get(1).path("earlyToleranceSeconds").asInt())
                .isEqualTo(300);

        assertThat(json(call(get("/api/v1/insights/otp?fromDate=2026-09-22&toDate=2026-09-28&routeType=0")
                                .header("Authorization", viewer())))
                        .path("items"))
                .extracting(i -> i.path("routeId").asString())
                .containsExactly("901");
        assertThat(json(call(get("/api/v1/insights/otp?fromDate=2026-09-22&toDate=2026-09-28&routeId=18&routeType=3,0")
                                .header("Authorization", viewer())))
                        .path("items"))
                .hasSize(1);
    }

    @Test
    @DisplayName("OTP needs the ACTIVE feed for the service day: without one it is a 503 with Retry-After 30")
    void otpWithoutAFeed() throws Exception {
        MockHttpServletResponse response = call(get("/api/v1/insights/otp").header("Authorization", viewer()));

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("30");
    }

    // ------------------------------------------------------------------------------------------------ E-15, E-16

    @Test
    @DisplayName("E-15, E-16 ticketing anomalies with the name and route of the sale point; unclassified; the detail")
    void ticketing() throws Exception {
        insertSalePoint();
        insertAnomaly(ANOMALY_1, minutesAgo(10), "fraud_suspect", 2);
        insertAnomaly(ANOMALY_2, minutesAgo(20), null, null);

        JsonNode all = json(call(get("/api/v1/insights/ticketing-anomalies").header("Authorization", viewer())));

        assertThat(ids(all)).containsExactly(ANOMALY_1, ANOMALY_2);
        JsonNode first = all.path("items").get(0);
        assertThat(first.path("salePointName").asString()).isEqualTo("Nicollet Mall Station kiosk 2");
        assertThat(first.path("routeId").asString()).isEqualTo("18");
        assertThat(first.path("refundRatio").decimalValue()).isEqualByComparingTo("0.48");
        assertThat(first.has("summary")).isFalse();
        assertThat(all.path("items").get(1).has("category")).isFalse();

        assertThat(ids(json(call(get("/api/v1/insights/ticketing-anomalies?category=unclassified")
                        .header("Authorization", viewer())))))
                .containsExactly(ANOMALY_2);
        assertThat(ids(json(call(get("/api/v1/insights/ticketing-anomalies?category=fraud_suspect&severity=2")
                        .header("Authorization", viewer())))))
                .containsExactly(ANOMALY_1);
        assertThat(ids(json(call(
                        get("/api/v1/insights/ticketing-anomalies?severity=0,1").header("Authorization", viewer())))))
                .isEmpty();
        assertThat(ids(json(call(get("/api/v1/insights/ticketing-anomalies?salePointId=SP-9999&trigger=BOTH")
                        .header("Authorization", viewer())))))
                .isEmpty();

        JsonNode detail = json(
                call(get("/api/v1/insights/ticketing-anomalies/" + ANOMALY_1).header("Authorization", viewer())));
        assertThat(detail.path("summary").path("txnCount").asInt()).isEqualTo(25);
        assertThat(detail.path("batchId").asString()).isEqualTo(BATCH);
    }

    // ------------------------------------------------------------------------------------------------ E-17, E-18

    @Test
    @DisplayName(
            "E-17 dispatch suggestions: filters; E-18 feedback with the operator, replacing the earlier one (EP-14)")
    void dispatch() throws Exception {
        insertBunching(BUNCHING, "18", minutesAgo(30), null, "OPEN");
        insertSuggestion(SUGGESTION, BUNCHING, "18", minutesAgo(29), "0.820");

        JsonNode list = json(
                call(get("/api/v1/insights/dispatch-suggestions?feedback=none").header("Authorization", viewer())));
        assertThat(ids(list)).containsExactly(SUGGESTION);
        assertThat(list.path("items").get(0).path("lowConfidence").asBoolean()).isFalse();
        assertThat(ids(json(call(get("/api/v1/insights/dispatch-suggestions?bunchingId=" + BUNCHING)
                        .header("Authorization", viewer())))))
                .containsExactly(SUGGESTION);

        MockHttpServletResponse accepted =
                call(post("/api/v1/insights/dispatch-suggestions/" + SUGGESTION + "/feedback")
                        .header("Authorization", operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedback\": \"accepted\"}"));
        JsonNode first = json(accepted);
        assertThat(first.path("operatorFeedback").asString()).isEqualTo("accepted");
        assertThat(first.path("feedbackBy").asString()).isEqualTo("user:operator");

        String other = JwtFixture.bearer(JwtFixture.token()
                .username("dispatcher")
                .roles("operator", "viewer")
                .build());
        JsonNode second = json(call(post("/api/v1/insights/dispatch-suggestions/" + SUGGESTION + "/feedback")
                .header("Authorization", other)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"feedback\": \"ignored\"}")));
        assertThat(second.path("operatorFeedback").asString()).isEqualTo("ignored");
        assertThat(second.path("feedbackBy").asString()).isEqualTo("user:dispatcher");
        assertThat(ownerText("SELECT operator_feedback || ' ' || feedback_by FROM insight.insight_dispatch_suggestion"))
                .isEqualTo("ignored user:dispatcher");
        assertThat(ids(json(call(get("/api/v1/insights/dispatch-suggestions?feedback=ignored")
                        .header("Authorization", viewer())))))
                .containsExactly(SUGGESTION);

        assertThat(call(post("/api/v1/insights/dispatch-suggestions/" + SUGGESTION + "/feedback")
                                .header("Authorization", operator())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"feedback\": \"later\"}"))
                        .getStatus())
                .isEqualTo(400);
        assertThat(call(post("/api/v1/insights/dispatch-suggestions/00000000-0000-0000-0000-0000000000ff/feedback")
                                .header("Authorization", operator())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"feedback\": \"accepted\"}"))
                        .getStatus())
                .isEqualTo(404);
    }
}
