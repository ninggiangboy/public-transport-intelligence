package dev.pti.api.alert.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.pti.api.alert.domain.Alert;
import dev.pti.api.alert.domain.AlertFixtures;
import dev.pti.api.alert.domain.AlertType;
import dev.pti.api.insight.adapter.in.web.InsightWebSupport;
import dev.pti.common.events.Audience;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/** E-20 {@code GET /alerts} and E-21 {@code POST /alerts/{id}/ack} (DOC-32 §5, EP-15, EP-16, EP-39). */
@ResourceLock("in-memory-alerts")
@ResourceLock("in-memory-insight")
@ResourceLock("freshness-probe-result")
class AlertEndpointsTest extends InsightWebSupport {

    private Alert disruption;
    private Alert bunching;
    private Alert infra;

    private static Alert at(Alert alert, Instant createdAt) {
        return new Alert(
                alert.id(),
                alert.type(),
                alert.severity(),
                alert.audience(),
                alert.routeId(),
                alert.refTable(),
                alert.refId(),
                alert.title(),
                alert.body(),
                createdAt,
                alert.acknowledgedBy(),
                alert.acknowledgedAt(),
                alert.resolvedAt());
    }

    @BeforeEach
    void data() {
        disruption = at(AlertFixtures.disruption(), ago(30));
        bunching = at(AlertFixtures.bunching(ago(20)), ago(20));
        infra = at(
                AlertFixtures.of(
                        AlertType.INFRA,
                        Audience.ENGINEERING,
                        null,
                        Map.of("annotations", Map.of("runbook_url", "https://runbooks.example/lag"))),
                ago(10));
        alerts.add(disruption);
        alerts.add(bunching);
        alerts.add(infra);
    }

    private MockHttpServletResponse list(String query, String bearer) throws Exception {
        var request = get("/api/v1/alerts" + query);
        return mvc.perform(bearer == null ? request : as(request, bearer))
                .andReturn()
                .getResponse();
    }

    private List<String> types(String query, String bearer) throws Exception {
        return okBody(list(query, bearer))
                .path("items")
                .valueStream()
                .map(item -> item.path("type").asString())
                .toList();
    }

    @Test
    @DisplayName(
            "Anonymous sees the public alerts only, without acknowledgement, with the body cut to the allowed keys")
    void anonymousSeesPublicOnly() throws Exception {
        MockHttpServletResponse response = list("", null);
        JsonNode items = okBody(response).path("items");

        assertThat(items).hasSize(1);
        JsonNode item = items.get(0);
        assertThat(item.path("type").asString()).isEqualTo("DISRUPTION");
        assertThat(item.has("acknowledgedBy")).isFalse();
        assertThat(item.has("acknowledgedAt")).isFalse();
        assertThat(item.path("body").propertyNames())
                .containsExactly(
                        "disruptionId", "directionId", "episodeStart", "currentAvgDelaySeconds", "affectedStopIds");
        assertThat(item.path("link").asString()).isEqualTo("/map?route=18&disruption=" + AlertFixtures.EPISODE);
        assertThat(response.getHeader("Vary")).isEqualTo("Authorization");
        assertThat(response.getHeader("X-Data-As-Of")).isNotNull();
    }

    @Test
    @DisplayName("A viewer sees every audience in full, newest first; the record has the members of DOC-32")
    void viewerSeesEverything() throws Exception {
        JsonNode items = okBody(list("", viewer())).path("items");

        assertThat(items)
                .extracting(item -> item.path("type").asString())
                .containsExactly("INFRA", "BUNCHING", "DISRUPTION");
        JsonNode disruptionItem = items.get(2);
        assertThat(disruptionItem.propertyNames())
                .containsExactly(
                        "id",
                        "type",
                        "severity",
                        "audience",
                        "routeId",
                        "refTable",
                        "refId",
                        "title",
                        "body",
                        "createdAt",
                        "acknowledgedBy",
                        "acknowledgedAt",
                        "link");
        assertThat(disruptionItem.path("body").path("zScore").decimalValue()).isEqualByComparingTo("3.98");
        assertThat(disruptionItem.path("acknowledgedBy").asString()).isEqualTo("user:operator");
    }

    @Test
    @DisplayName("EP-39 the link of each type, and an infrastructure alert with a runbook links to it")
    void links() throws Exception {
        JsonNode items = okBody(list("", viewer())).path("items");

        assertThat(items.get(0).path("link").asString()).isEqualTo("https://runbooks.example/lag");
        assertThat(items.get(1).path("link").asString())
                .isEqualTo("/map?route=18&bunching=6f1c2a9e-4b1d-5c8e-9a2f-3d4e5f6a7b8c");
    }

    @Test
    @DisplayName("EP-15 an anonymous caller who asks for OPERATIONS or ENGINEERING gets a 403; PUBLIC is fine")
    void anonymousAsksForAnotherAudience() throws Exception {
        assertProblem(list("?audience=OPERATIONS", null), 403, "forbidden");
        assertProblem(list("?audience=PUBLIC&audience=ENGINEERING", null), 403, "forbidden");
        assertThat(types("?audience=PUBLIC", null)).containsExactly("DISRUPTION");
    }

    @Test
    @DisplayName("Filters: audience, type, severity, routeId, state; several values by repeating or by commas")
    void filters() throws Exception {
        assertThat(types("?audience=ENGINEERING", viewer())).containsExactly("INFRA");
        assertThat(types("?audience=ENGINEERING,OPERATIONS", viewer())).containsExactly("INFRA", "BUNCHING");
        assertThat(types("?type=BUNCHING&type=DISRUPTION", viewer())).containsExactly("BUNCHING", "DISRUPTION");
        assertThat(types("?severity=0", viewer())).isEmpty();
        assertThat(types("?routeId=18", viewer())).containsExactly("BUNCHING", "DISRUPTION");
        assertThat(types("?state=unacknowledged", viewer())).containsExactly("INFRA", "BUNCHING");
        assertThat(types("?state=open", viewer())).hasSize(3);
        assertThat(types("?state=all", viewer())).hasSize(3);
    }

    @Test
    @DisplayName("since lists the alerts created after that time, for polling; it cannot be combined with from")
    void since() throws Exception {
        assertThat(types("?since=-25m", viewer())).containsExactly("INFRA", "BUNCHING");
        assertThat(types("?since=-5m", viewer())).isEmpty();
        assertValidationError(list("?since=-25m&from=-1h", viewer()), "since");
        assertValidationError(list("?since=yesterday", viewer()), "since");
    }

    @Test
    @DisplayName("from and to bound created_at; the default range is 24 hours")
    void range() throws Exception {
        alerts.add(at(AlertFixtures.of(AlertType.FEED_STALE, Audience.ENGINEERING, null, Map.of()), ago(60 * 30)));

        assertThat(types("", viewer())).hasSize(3);
        assertThat(types("?from=-2d", viewer())).hasSize(4);
        assertThat(types("?from=-40m&to=-15m", viewer())).containsExactly("BUNCHING", "DISRUPTION");
    }

    @Test
    @DisplayName("Bad values are a 400 on their field, the valid ones are named")
    void badParameters() throws Exception {
        assertValidationError(list("?audience=ALL", viewer()), "audience");
        assertValidationError(list("?type=FIRE", viewer()), "type");
        assertValidationError(list("?severity=3", viewer()), "severity");
        assertValidationError(list("?state=closed", viewer()), "state");
        assertThat(body(list("?state=closed", viewer()))
                        .path("errors")
                        .get(0)
                        .path("message")
                        .asString())
                .contains("open", "unacknowledged", "all");
        assertValidationError(list("?routeID=18", viewer()), "routeID");
        assertValidationError(list("?limit=0", viewer()), "limit");
    }

    @Test
    @DisplayName("Paging by createdAt and id, with a cursor of the same filters")
    void paging() throws Exception {
        JsonNode first = okBody(list("?limit=2", viewer()));
        String cursor = first.path("nextCursor").asString();

        JsonNode second = okBody(list("?limit=2&cursor=" + cursor, viewer()));
        assertThat(second.path("items")).hasSize(1);
        assertThat(second.has("nextCursor")).isFalse();
        assertValidationError(list("?limit=2&type=INFRA&cursor=" + cursor, viewer()), "cursor");
    }

    // ------------------------------------------------------------------------------------------------ E-21

    private MockHttpServletResponse ack(String id, String bearer) throws Exception {
        var request = post("/api/v1/alerts/" + id + "/ack");
        return mvc.perform(bearer == null ? request : as(request, bearer))
                .andReturn()
                .getResponse();
    }

    @Test
    @DisplayName("EP-16 acknowledging twice: 200 both times, the first operator stays, one alert.updated event")
    void acknowledgeTwice() throws Exception {
        JsonNode first = okBody(ack(bunching.id().toString(), operator()));
        String other = dev.pti.api.testing.JwtFixture.bearer(dev.pti.api.testing.JwtFixture.token()
                .username("dispatcher")
                .roles("operator", "viewer")
                .build());
        JsonNode second = okBody(ack(bunching.id().toString(), other));

        assertThat(first.path("acknowledgedBy").asString()).isEqualTo("user:operator");
        assertThat(first.path("acknowledgedAt").asString()).isEqualTo("2026-09-29T21:14:02.123Z");
        assertThat(second).isEqualTo(first);
        assertThat(uiEvents.events()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("alert.updated");
            assertThat(event.audience()).isEqualTo(Audience.OPERATIONS);
        });
        assertThat(okBody(list("?type=BUNCHING", viewer()))
                        .path("items")
                        .get(0)
                        .path("acknowledgedBy")
                        .asString())
                .isEqualTo("user:operator");
    }

    @Test
    @DisplayName("Acknowledging an unknown or malformed id is a 404 and publishes nothing")
    void acknowledgeUnknown() throws Exception {
        assertProblem(ack(UUID.randomUUID().toString(), operator()), 404, "not-found");
        assertProblem(ack("nope", operator()), 404, "not-found");
        assertThat(uiEvents.events()).isEmpty();
    }

    @Test
    @DisplayName("Acknowledging is for operators: a viewer is a 403, no token a 401, and nothing is written")
    void acknowledgeRoles() throws Exception {
        assertThat(ack(bunching.id().toString(), viewer()).getStatus()).isEqualTo(403);
        assertThat(ack(bunching.id().toString(), null).getStatus()).isEqualTo(401);
        assertThat(alerts.statements).isEmpty();
        assertThat(uiEvents.events()).isEmpty();
    }
}
