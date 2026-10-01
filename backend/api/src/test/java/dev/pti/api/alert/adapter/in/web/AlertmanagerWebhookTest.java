package dev.pti.api.alert.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.pti.api.alert.domain.Alert;
import dev.pti.api.alert.domain.AlertType;
import dev.pti.api.insight.adapter.in.web.InsightWebSupport;
import dev.pti.common.events.Audience;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

/** E-80 {@code POST /internal/alerts/alertmanager} (DOC-32 §11, EP-35, EP-36). */
@ResourceLock("in-memory-alerts")
@ResourceLock("in-memory-insight")
@ResourceLock("freshness-probe-result")
class AlertmanagerWebhookTest extends InsightWebSupport {

    private static final String PATH = "/internal/alerts/alertmanager";

    @Autowired
    private MeterRegistry meters;

    private static String payload(String status, String fingerprint, String startsAt, String alertName) {
        return """
                {
                  "version": "4",
                  "groupKey": "{}:{alertname=\\"%3$s\\"}",
                  "status": "%1$s",
                  "receiver": "default",
                  "groupLabels": {"alertname": "%3$s"},
                  "commonLabels": {"alertname": "%3$s"},
                  "externalURL": "http://alertmanager:9093",
                  "alerts": [{
                    "status": "%1$s",
                    "labels": {"alertname": "%3$s", "severity": "critical", "source": "vehicle_positions"},
                    "annotations": {"summary": "GTFS-rt vehicle positions stopped", "runbook_url": "https://runbooks.example/stale"},
                    "startsAt": "%2$s",
                    "endsAt": "0001-01-01T00:00:00Z",
                    "generatorURL": "http://prometheus:9090/graph?g0.expr=up",
                    "fingerprint": "%4$s"
                  }]
                }
                """.formatted(status, startsAt, alertName, fingerprint);
    }

    private MockHttpServletResponse send(String body, String token) throws Exception {
        var request = post(PATH).contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(token == null ? request : request.header("Authorization", "Bearer " + token))
                .andReturn()
                .getResponse();
    }

    private MockHttpServletResponse send(String body) throws Exception {
        return send(body, WEBHOOK_TOKEN);
    }

    private double count(String outcome) {
        var counter = meters.find("pti.alert.webhook").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("EP-35 a wrong token is a 401 and nothing is written; no token the same")
    void tokenIsRequired() throws Exception {
        String body = payload("firing", "f1", "2026-09-29T21:00:00.000Z", "GtfsRtFeedStale");

        assertProblem(send(body, "wrong"), 401, "unauthorized");
        assertProblem(send(body, null), 401, "unauthorized");
        assertThat(alerts.alerts).isEmpty();
        assertThat(alerts.statements).isEmpty();
    }

    @Test
    @DisplayName("EP-36 GtfsRtFeedStale: 204, type FEED_STALE, audience ENGINEERING, severity 2, one alert.created")
    void firingFeedStale() throws Exception {
        MockHttpServletResponse response = send(payload("firing", "f1", "2026-09-29T21:00:00.000Z", "GtfsRtFeedStale"));

        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(response.getContentAsString()).isEmpty();
        Alert alert = alerts.alerts.values().iterator().next();
        assertThat(alert.type()).isEqualTo(AlertType.FEED_STALE);
        assertThat(alert.audience()).isEqualTo(Audience.ENGINEERING);
        assertThat(alert.severity()).isEqualTo(2);
        assertThat(alert.title()).isEqualTo("GTFS-rt vehicle positions stopped");
        assertThat(alert.body().get("alertname")).isEqualTo("GtfsRtFeedStale");
        assertThat(uiEvents.events()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("alert.created");
            assertThat(event.audience()).isEqualTo(Audience.ENGINEERING);
            assertThat(event.key()).isEqualTo(alert.id().toString());
        });
    }

    @Test
    @DisplayName("EP-35 firing twice with the same fingerprint and startsAt: one row, one event; then resolved once")
    void duplicateAndResolved() throws Exception {
        String firing = payload("firing", "f2", "2026-09-29T21:00:00.000Z", "ConsumerLagHigh");
        double created = count("created");
        double duplicates = count("duplicate");
        double resolved = count("resolved");

        assertThat(send(firing).getStatus()).isEqualTo(204);
        assertThat(send(firing).getStatus()).isEqualTo(204);
        assertThat(alerts.alerts).hasSize(1);
        assertThat(uiEvents.events()).hasSize(1);

        String resolution = payload("resolved", "f2", "2026-09-29T21:00:00.000Z", "ConsumerLagHigh");
        assertThat(send(resolution).getStatus()).isEqualTo(204);
        assertThat(send(resolution).getStatus()).isEqualTo(204);

        assertThat(alerts.alerts.values().iterator().next().resolvedAt()).isNotNull();
        assertThat(uiEvents.events())
                .extracting(event -> event.type())
                .containsExactly("alert.created", "alert.updated");
        assertThat(count("created") - created).isEqualTo(1);
        assertThat(count("duplicate") - duplicates).isEqualTo(1);
        assertThat(count("resolved") - resolved).isEqualTo(1);
    }

    @Test
    @DisplayName("A notice with an unknown alert name is INFRA, and the link of the event is its runbook")
    void infraWithRunbook() throws Exception {
        send(payload("firing", "f3", "2026-09-29T21:00:00Z", "DatabaseBottleneck"));

        assertThat(uiEvents.events().getFirst().data())
                .containsEntry("type", "INFRA")
                .containsEntry("link", "https://runbooks.example/stale");
    }

    @Test
    @DisplayName("An empty list of alerts is a 204 that does nothing")
    void empty() throws Exception {
        assertThat(send("{\"alerts\": []}").getStatus()).isEqualTo(204);
        assertThat(alerts.statements).isEmpty();
    }

    @Test
    @DisplayName(
            "A body that is not a webhook payload is a 400 and is counted as rejected; Alertmanager sends it again")
    void rejected() throws Exception {
        double before = count("rejected");

        assertValidationError(send("not json"), "body");
        assertValidationError(send("{}"), "alerts");
        assertValidationError(send("{\"alerts\": [{\"status\": \"firing\"}]}"), "alerts[0].fingerprint");
        assertValidationError(send(payload("pending", "f4", "2026-09-29T21:00:00Z", "X")), "alerts[0].status");
        assertValidationError(send(payload("firing", "f4", "yesterday", "X")), "alerts[0].startsAt");

        assertThat(count("rejected") - before).isEqualTo(5);
        assertThat(alerts.statements).isEmpty();
    }

    @Test
    @DisplayName("Members of the payload that the API does not read are ignored, as Alertmanager may add more")
    void unknownMembersAreIgnored() throws Exception {
        String body = payload("firing", "f5", "2026-09-29T21:00:00Z", "GtfsRtFeedStale")
                .replace("\"version\": \"4\",", "\"version\": \"4\", \"truncatedAlerts\": 0, \"newThing\": {},");

        assertThat(send(body).getStatus()).isEqualTo(204);
        assertThat(alerts.alerts).hasSize(1);
    }

    @Test
    @DisplayName("The webhook is not rate limited and not a user endpoint: a user token does not open it")
    void userTokenDoesNotOpenIt() throws Exception {
        assertProblem(
                mvc.perform(post(PATH)
                                .header("Authorization", operator())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"alerts\": []}"))
                        .andReturn()
                        .getResponse(),
                401,
                "unauthorized");
    }
}
