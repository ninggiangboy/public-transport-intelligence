package dev.pti.api.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.pti.api.insight.InsightIntegrationSupport;
import dev.pti.api.testing.JwtFixture;
import dev.pti.common.events.UiEvent;
import dev.pti.common.events.UiEventPublisher;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * E-20, E-21 and E-80 end to end against the real warehouse: the SQL of {@code sql/alert/}, the grants of {@code
 * replay_operator}, and the UI events of the writes, which a recording publisher keeps in place of the broker (the
 * envelope on a real broker is {@code UiEventsKafkaIT}).
 */
@Import(AlertEndpointsIT.RecordedEvents.class)
class AlertEndpointsIT extends InsightIntegrationSupport {

    private static final String PUBLIC_ALERT = "00000000-0000-0000-0000-0000000000a1";
    private static final String OPS_ALERT = "00000000-0000-0000-0000-0000000000a2";
    private static final String ENG_ALERT = "00000000-0000-0000-0000-0000000000a3";
    private static final String WEBHOOK_TOKEN = "t0ken-of-the-alertmanager-webhook";

    /** The events the writes made, instead of a broker. */
    @TestConfiguration(proxyBeanMethods = false)
    static class RecordedEvents {

        @Bean
        @Primary
        RecordingPublisher recordingPublisher() {
            return new RecordingPublisher();
        }
    }

    static final class RecordingPublisher implements UiEventPublisher {

        final List<UiEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void publish(UiEvent event) {
            events.add(event);
        }

        @Override
        public void publishAll(List<UiEvent> batch) {
            batch.forEach(this::publish);
        }
    }

    private static final Path TOKEN_FILE = tokenFile();

    @DynamicPropertySource
    static void webhookToken(DynamicPropertyRegistry registry) {
        registry.add("pti.api.alert-webhook.token-file", TOKEN_FILE::toString);
    }

    private static Path tokenFile() {
        try {
            Path file = Files.createTempFile("webhook-token", ".txt");
            file.toFile().deleteOnExit();
            Files.writeString(file, WEBHOOK_TOKEN + "\n");
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper mapper;

    @Autowired
    private RecordingPublisher published;

    @BeforeEach
    void forgetEvents() {
        published.events.clear();
    }

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

    private void threeAlerts() {
        insertAlert(PUBLIC_ALERT, "DISRUPTION", "PUBLIC", "18", minutesAgo(30), "disruption:a1");
        insertAlert(OPS_ALERT, "BUNCHING", "OPERATIONS", "18", minutesAgo(20), "bunching:a2");
        insertAlert(ENG_ALERT, "INFRA", "ENGINEERING", null, minutesAgo(10), "am:a3:2026-09-29T21:00:00Z");
    }

    // ------------------------------------------------------------------------------------------------ E-20

    @Test
    @DisplayName("E-20 audience: anonymous sees PUBLIC only (403 for another), a viewer every audience, newest first")
    void audiences() throws Exception {
        threeAlerts();

        assertThat(ids(json(call(get("/api/v1/alerts"))))).containsExactly(PUBLIC_ALERT);
        assertThat(call(get("/api/v1/alerts?audience=OPERATIONS")).getStatus()).isEqualTo(403);
        assertThat(ids(json(call(get("/api/v1/alerts").header("Authorization", viewer())))))
                .containsExactly(ENG_ALERT, OPS_ALERT, PUBLIC_ALERT);
        assertThat(ids(json(call(
                        get("/api/v1/alerts?audience=OPERATIONS,ENGINEERING").header("Authorization", viewer())))))
                .containsExactly(ENG_ALERT, OPS_ALERT);
        assertThat(ids(json(call(get("/api/v1/alerts?audience=ENGINEERING").header("Authorization", viewer())))))
                .containsExactly(ENG_ALERT);
    }

    @Test
    @DisplayName("E-20 filters: type, severity, routeId, state; the link and the as-of header")
    void filters() throws Exception {
        threeAlerts();
        asOwner("UPDATE ops.alert_event SET acknowledged_by = 'user:operator', acknowledged_at = now() WHERE id = '"
                + OPS_ALERT + "'");
        asOwner("UPDATE ops.alert_event SET resolved_at = now() WHERE id = '" + PUBLIC_ALERT + "'");

        assertThat(ids(json(call(get("/api/v1/alerts?type=BUNCHING&type=INFRA").header("Authorization", viewer())))))
                .containsExactly(ENG_ALERT, OPS_ALERT);
        assertThat(ids(json(call(get("/api/v1/alerts?severity=2").header("Authorization", viewer())))))
                .isEmpty();
        assertThat(ids(json(call(get("/api/v1/alerts?routeId=18").header("Authorization", viewer())))))
                .containsExactly(OPS_ALERT, PUBLIC_ALERT);
        assertThat(ids(json(call(get("/api/v1/alerts?state=open").header("Authorization", viewer())))))
                .containsExactly(ENG_ALERT, OPS_ALERT);
        assertThat(ids(json(call(get("/api/v1/alerts?state=unacknowledged").header("Authorization", viewer())))))
                .containsExactly(ENG_ALERT);

        MockHttpServletResponse response = call(get("/api/v1/alerts?type=DISRUPTION"));
        JsonNode item = json(response).path("items").get(0);
        assertThat(item.path("link").asString()).isEqualTo("/map?route=18");
        assertThat(item.path("resolvedAt").asString()).isNotEmpty();
        assertThat(item.path("body").path("k"))
                .as("the public projection drops unknown keys")
                .isEmpty();
        assertThat(response.getHeader("X-Data-As-Of")).isNotNull();
    }

    @Test
    @DisplayName("E-20 since lists the alerts created after it; paging over equal microsecond timestamps loses none")
    void sinceAndPaging() throws Exception {
        threeAlerts();
        assertThat(ids(json(call(get("/api/v1/alerts?since=" + minutesAgo(25)).header("Authorization", viewer())))))
                .containsExactly(ENG_ALERT, OPS_ALERT);

        asOwner("DELETE FROM ops.alert_event");
        String sameInstant = "2026-09-29T21:00:00.123456Z";
        for (int i = 1; i <= 5; i++) {
            insertAlert(
                    "00000000-0000-0000-0000-%012d".formatted(i),
                    "INFRA",
                    "ENGINEERING",
                    null,
                    sameInstant,
                    "am:p%d".formatted(i));
        }
        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = json(call(get("/api/v1/alerts?from=2026-09-29T00:00:00Z&to=2026-09-30T00:00:00Z&limit=2"
                            + (cursor == null ? "" : "&cursor=" + cursor))
                    .header("Authorization", viewer())));
            seen.addAll(ids(page));
            cursor = page.has("nextCursor") ? page.path("nextCursor").asString() : null;
        } while (cursor != null);

        assertThat(seen)
                .containsExactly(
                        "00000000-0000-0000-0000-000000000005",
                        "00000000-0000-0000-0000-000000000004",
                        "00000000-0000-0000-0000-000000000003",
                        "00000000-0000-0000-0000-000000000002",
                        "00000000-0000-0000-0000-000000000001");
    }

    // ------------------------------------------------------------------------------------------------ E-21

    @Test
    @DisplayName(
            "EP-16 E-21 acknowledging twice: the first operator stays, the database has one acknowledgement, one event")
    void acknowledge() throws Exception {
        threeAlerts();

        JsonNode first = json(call(post("/api/v1/alerts/" + OPS_ALERT + "/ack").header("Authorization", operator())));
        String other = JwtFixture.bearer(JwtFixture.token()
                .username("dispatcher")
                .roles("operator", "viewer")
                .build());
        JsonNode second = json(call(post("/api/v1/alerts/" + OPS_ALERT + "/ack").header("Authorization", other)));

        assertThat(first.path("acknowledgedBy").asString()).isEqualTo("user:operator");
        assertThat(second.path("acknowledgedBy").asString()).isEqualTo("user:operator");
        assertThat(second.path("acknowledgedAt")).isEqualTo(first.path("acknowledgedAt"));
        assertThat(ownerText("SELECT acknowledged_by FROM ops.alert_event WHERE id = '" + OPS_ALERT + "'"))
                .isEqualTo("user:operator");
        assertThat(published.events).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("alert.updated");
            assertThat(event.audience().name()).isEqualTo("OPERATIONS");
            assertThat(event.data()).containsEntry("acknowledgedBy", "user:operator");
        });
    }

    @Test
    @DisplayName("E-21 an alert that is resolved can be acknowledged; an unknown one is a 404; a viewer a 403")
    void acknowledgeEdges() throws Exception {
        threeAlerts();
        asOwner("UPDATE ops.alert_event SET resolved_at = now() WHERE id = '" + OPS_ALERT + "'");

        JsonNode resolved =
                json(call(post("/api/v1/alerts/" + OPS_ALERT + "/ack").header("Authorization", operator())));

        assertThat(resolved.path("resolvedAt").asString()).isNotEmpty();
        assertThat(call(post("/api/v1/alerts/00000000-0000-0000-0000-0000000000ff/ack")
                                .header("Authorization", operator()))
                        .getStatus())
                .isEqualTo(404);
        assertThat(call(post("/api/v1/alerts/" + ENG_ALERT + "/ack").header("Authorization", viewer()))
                        .getStatus())
                .isEqualTo(403);
        assertThat(ownerText("SELECT acknowledged_by FROM ops.alert_event WHERE id = '" + ENG_ALERT + "'"))
                .isNull();
    }

    // ------------------------------------------------------------------------------------------------ E-80

    private static String notice(String status, String fingerprint, String startsAt, String alertName) {
        return """
                {"version": "4", "status": "%s", "alerts": [{
                  "status": "%s", "fingerprint": "%s", "startsAt": "%s",
                  "labels": {"alertname": "%s", "severity": "critical"},
                  "annotations": {"summary": "GTFS-rt vehicle positions stopped"},
                  "generatorURL": "http://prometheus:9090/graph"}]}""".formatted(status, status, fingerprint, startsAt, alertName);
    }

    private MockHttpServletResponse webhook(String body) throws Exception {
        return call(post("/internal/alerts/alertmanager")
                .header("Authorization", "Bearer " + WEBHOOK_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    @DisplayName(
            "EP-35, EP-36 webhook: firing twice is one row; FEED_STALE for engineering with severity 2; resolved once")
    void webhookLifecycle() throws Exception {
        String firing = notice("firing", "9f8e7d6c", "2026-09-29T21:00:00.000Z", "GtfsRtFeedStale");

        assertThat(webhook(firing).getStatus()).isEqualTo(204);
        assertThat(webhook(firing).getStatus()).isEqualTo(204);

        assertThat(ownerLong("SELECT count(*) FROM ops.alert_event")).isEqualTo(1);
        assertThat(ownerText(
                        "SELECT type || ' ' || audience || ' ' || severity || ' ' || dedup_key FROM ops.alert_event"))
                .isEqualTo("FEED_STALE ENGINEERING 2 am:9f8e7d6c:2026-09-29T21:00:00.000Z");
        assertThat(ownerText("SELECT title FROM ops.alert_event")).isEqualTo("GTFS-rt vehicle positions stopped");
        assertThat(ownerText("SELECT body->>'alertname' FROM ops.alert_event")).isEqualTo("GtfsRtFeedStale");
        assertThat(ownerText("SELECT resolved_at FROM ops.alert_event")).isNull();
        assertThat(published.events).extracting(UiEvent::type).containsExactly("alert.created");

        String resolved = notice("resolved", "9f8e7d6c", "2026-09-29T21:00:00.000Z", "GtfsRtFeedStale");
        assertThat(webhook(resolved).getStatus()).isEqualTo(204);
        assertThat(webhook(resolved).getStatus()).isEqualTo(204);

        assertThat(ownerText("SELECT resolved_at FROM ops.alert_event")).isNotNull();
        assertThat(published.events).extracting(UiEvent::type).containsExactly("alert.created", "alert.updated");
        assertThat(published.events.get(1).data()).containsKey("resolvedAt");

        JsonNode viewerView =
                json(call(get("/api/v1/alerts?audience=ENGINEERING").header("Authorization", viewer())));
        assertThat(viewerView.path("items").get(0).path("type").asString()).isEqualTo("FEED_STALE");
        assertThat(viewerView.path("items").get(0).path("link").asString()).isEqualTo("/ops/jobs?kind=STREAM");
        assertThat(ids(json(call(get("/api/v1/alerts"))))).as("never public").isEmpty();
    }

    @Test
    @DisplayName("EP-35 a wrong token is a 401 and writes nothing; a malformed body is a 400")
    void webhookRefusals() throws Exception {
        assertThat(call(post("/internal/alerts/alertmanager")
                                .header("Authorization", "Bearer wrong")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(notice("firing", "f", "2026-09-29T21:00:00Z", "X")))
                        .getStatus())
                .isEqualTo(401);
        assertThat(webhook("{\"alerts\": [{\"status\": \"firing\"}]}").getStatus())
                .isEqualTo(400);
        assertThat(ownerLong("SELECT count(*) FROM ops.alert_event")).isZero();
        assertThat(published.events).isEmpty();
    }

    @Test
    @DisplayName("A resolved notice of an alert that was never received changes nothing")
    void unknownResolved() throws Exception {
        assertThat(webhook(notice("resolved", "ffff", "2026-09-29T21:00:00Z", "X"))
                        .getStatus())
                .isEqualTo(204);

        assertThat(ownerLong("SELECT count(*) FROM ops.alert_event")).isZero();
        assertThat(published.events).isEmpty();
    }
}
