package dev.pti.api.insight.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.pti.api.insight.domain.DispatchSuggestion;
import dev.pti.api.insight.domain.InsightFixtures;
import dev.pti.api.testing.JwtFixture;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/** E-17 {@code GET /insights/dispatch-suggestions} and E-18 {@code POST .../{id}/feedback} (DOC-32 §4, EP-14). */
@ResourceLock("in-memory-insight")
class DispatchSuggestionEndpointsTest extends InsightWebSupport {

    private static final UUID FIRST = InsightFixtures.SUGGESTION_ID;
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID THIRD = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @BeforeEach
    void data() {
        add(FIRST, "18", ago(10), "0.820");
        add(SECOND, "20", ago(20), "0.450");
        add(THIRD, "18", ago(30), "0.900");
    }

    private void add(UUID id, String route, java.time.Instant createdAt, String confidence) {
        UUID bunching = id.equals(FIRST) ? InsightFixtures.BUNCHING_ID : UUID.randomUUID();
        DispatchSuggestion suggestion = InsightFixtures.suggestion(id, bunching, route, createdAt, confidence);
        insight.suggestions.put(id, suggestion);
    }

    private MockHttpServletResponse list(String query) throws Exception {
        return mvc.perform(as(get("/api/v1/insights/dispatch-suggestions" + query), viewer()))
                .andReturn()
                .getResponse();
    }

    private List<String> ids(String query) throws Exception {
        return okBody(list(query))
                .path("items")
                .valueStream()
                .map(item -> item.path("id").asString())
                .toList();
    }

    private MockHttpServletResponse feedback(UUID id, String bearer, String body) throws Exception {
        return mvc.perform(as(post("/api/v1/insights/dispatch-suggestions/" + id + "/feedback"), bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse();
    }

    @Test
    @DisplayName("A suggestion has its confidence, lowConfidence below 0.6, and the state it was made from")
    void shape() throws Exception {
        JsonNode items = okBody(list("")).path("items");

        assertThat(items)
                .extracting(item -> item.path("id").asString())
                .containsExactly(FIRST.toString(), SECOND.toString(), THIRD.toString());
        JsonNode first = items.get(0);
        assertThat(first.propertyNames())
                .containsExactly(
                        "id",
                        "bunchingId",
                        "routeId",
                        "action",
                        "actionConfidence",
                        "lowConfidence",
                        "modelVersion",
                        "createdAt",
                        "stateSnapshot");
        assertThat(first.path("lowConfidence").asBoolean()).isFalse();
        assertThat(items.get(1).path("lowConfidence").asBoolean()).isTrue();
        assertThat(first.path("stateSnapshot").path("task").asString()).startsWith("Suggest one dispatch action");
    }

    @Test
    @DisplayName("Filters: route, bunching episode, feedback accepted, ignored or none")
    void filters() throws Exception {
        feedback(SECOND, operator(), "{\"feedback\": \"accepted\"}");
        feedback(THIRD, operator(), "{\"feedback\": \"ignored\"}");

        assertThat(ids("?routeId=18")).containsExactly(FIRST.toString(), THIRD.toString());
        assertThat(ids("?bunchingId=" + InsightFixtures.BUNCHING_ID)).containsExactly(FIRST.toString());
        assertThat(ids("?feedback=none")).containsExactly(FIRST.toString());
        assertThat(ids("?feedback=accepted")).containsExactly(SECOND.toString());
        assertThat(ids("?feedback=ignored")).containsExactly(THIRD.toString());
    }

    @Test
    @DisplayName("Bad values are a 400 on their field")
    void badParameters() throws Exception {
        assertValidationError(list("?feedback=maybe"), "feedback");
        assertValidationError(list("?bunchingId=nope"), "bunchingId");
        assertValidationError(list("?routeID=18"), "routeID");
        assertValidationError(list("?limit=501"), "limit");
    }

    @Test
    @DisplayName("Paging by createdAt and id with a cursor of the same filters")
    void paging() throws Exception {
        JsonNode first = okBody(list("?limit=2"));
        String cursor = first.path("nextCursor").asString();

        assertThat(okBody(list("?limit=2&cursor=" + cursor)).path("items")).hasSize(1);
        assertValidationError(list("?limit=2&routeId=18&cursor=" + cursor), "cursor");
    }

    @Test
    @DisplayName("EP-14 accepted and then ignored: the last answer stands and feedbackBy is the last operator")
    void lastAnswerStands() throws Exception {
        JsonNode accepted = okBody(feedback(FIRST, operator(), "{\"feedback\": \"accepted\"}"));
        assertThat(accepted.path("operatorFeedback").asString()).isEqualTo("accepted");
        assertThat(accepted.path("feedbackBy").asString()).isEqualTo("user:operator");
        assertThat(accepted.path("feedbackAt").asString()).isEqualTo("2026-09-29T21:14:02Z");

        String other = JwtFixture.bearer(JwtFixture.token()
                .username("dispatcher")
                .roles("operator", "viewer")
                .build());
        JsonNode ignored = okBody(feedback(FIRST, other, "{\"feedback\": \"ignored\"}"));

        assertThat(ignored.path("operatorFeedback").asString()).isEqualTo("ignored");
        assertThat(ignored.path("feedbackBy").asString()).isEqualTo("user:dispatcher");
        assertThat(okBody(list(""))
                        .path("items")
                        .get(0)
                        .path("operatorFeedback")
                        .asString())
                .isEqualTo("ignored");
    }

    @Test
    @DisplayName("Feedback is 200 with the element of E-17; the same answer again is 200 and the same row")
    void sameAnswerAgain() throws Exception {
        JsonNode once = okBody(feedback(FIRST, operator(), "{\"feedback\": \"accepted\"}"));
        JsonNode twice = okBody(feedback(FIRST, operator(), "{\"feedback\": \"accepted\"}"));

        assertThat(twice).isEqualTo(once);
        assertThat(once.path("lowConfidence").asBoolean()).isFalse();
    }

    @Test
    @DisplayName(
            "A value other than accepted or ignored, a missing one or another member is a 400; 404 for an unknown id")
    void feedbackErrors() throws Exception {
        assertValidationError(feedback(FIRST, operator(), "{\"feedback\": \"later\"}"), "feedback");
        assertValidationError(feedback(FIRST, operator(), "{}"), "feedback");
        assertProblem(
                feedback(FIRST, operator(), "{\"feedback\": \"accepted\", \"note\": \"x\"}"), 400, "validation-error");
        assertProblem(feedback(UUID.randomUUID(), operator(), "{\"feedback\": \"accepted\"}"), 404, "not-found");
        assertProblem(
                mvc.perform(as(post("/api/v1/insights/dispatch-suggestions/nope/feedback"), operator())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"feedback\": \"accepted\"}"))
                        .andReturn()
                        .getResponse(),
                404,
                "not-found");
        assertThat(insight.suggestions.get(FIRST).operatorFeedback()).isNull();
    }

    @Test
    @DisplayName("Feedback is for operators: a viewer is a 403, no token a 401; the list is for viewers")
    void roles() throws Exception {
        assertThat(feedback(FIRST, viewer(), "{\"feedback\": \"accepted\"}").getStatus())
                .isEqualTo(403);
        assertThat(mvc.perform(post("/api/v1/insights/dispatch-suggestions/" + FIRST + "/feedback")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"feedback\": \"accepted\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
        assertThat(mvc.perform(get("/api/v1/insights/dispatch-suggestions"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
        assertThat(insight.suggestions.get(FIRST).operatorFeedback()).isNull();
    }
}
