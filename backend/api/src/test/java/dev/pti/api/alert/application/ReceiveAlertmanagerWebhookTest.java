package dev.pti.api.alert.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.api.alert.application.port.WebhookMetrics;
import dev.pti.api.alert.domain.Alert;
import dev.pti.api.alert.domain.AlertType;
import dev.pti.api.alert.domain.AlertmanagerAlert;
import dev.pti.api.alert.domain.AlertmanagerAlert.Status;
import dev.pti.common.events.Audience;
import dev.pti.common.events.UiEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@code POST /internal/alerts/alertmanager} (DOC-32 E-80, EP-35, EP-36). */
class ReceiveAlertmanagerWebhookTest extends AlertUseCaseSupport {

    private final List<String> outcomes = new ArrayList<>();
    private ReceiveAlertmanagerWebhook webhook;

    @BeforeEach
    void setUp() {
        webhook = new ReceiveAlertmanagerWebhook(alerts.store, events, outcomes::add, clock, tx);
    }

    private static AlertmanagerAlert notice(Status status, String fingerprint, String startsAt, String alertName) {
        return new AlertmanagerAlert(
                status,
                fingerprint,
                startsAt,
                Map.of("alertname", alertName, "severity", "critical"),
                Map.of("summary", "Summary of " + alertName),
                null);
    }

    private static AlertmanagerAlert stale(Status status) {
        return notice(status, "9f8e7d6c", "2026-09-29T21:00:00.000Z", "GtfsRtFeedStale");
    }

    @Test
    @DisplayName("EP-35 a firing notice inserts the alert and publishes alert.created for engineering")
    void firing() {
        webhook.execute(List.of(stale(Status.FIRING)));

        assertThat(alerts.alerts.values()).singleElement().satisfies(alert -> {
            assertThat(alert.type()).isEqualTo(AlertType.FEED_STALE);
            assertThat(alert.audience()).isEqualTo(Audience.ENGINEERING);
            assertThat(alert.severity()).isEqualTo(2);
            assertThat(alert.resolvedAt()).isNull();
        });
        assertThat(outcomes).containsExactly(WebhookMetrics.CREATED);
        assertThat(recorded.events()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("alert.created");
            assertThat(event.audience()).isEqualTo(Audience.ENGINEERING);
            assertThat(event.data()).containsEntry("type", "FEED_STALE").containsEntry("link", "/ops/jobs?kind=STREAM");
        });
    }

    @Test
    @DisplayName("EP-35 the same notice twice (same fingerprint and startsAt) is one row and one event")
    void duplicate() {
        webhook.execute(List.of(stale(Status.FIRING)));
        webhook.execute(List.of(stale(Status.FIRING)));

        assertThat(alerts.alerts).hasSize(1);
        assertThat(recorded.events()).hasSize(1);
        assertThat(outcomes).containsExactly(WebhookMetrics.CREATED, WebhookMetrics.DUPLICATE);
    }

    @Test
    @DisplayName("The same alert starting again later is a new alert")
    void firesAgain() {
        webhook.execute(List.of(stale(Status.FIRING)));
        webhook.execute(List.of(notice(Status.FIRING, "9f8e7d6c", "2026-09-29T22:00:00.000Z", "GtfsRtFeedStale")));

        assertThat(alerts.alerts).hasSize(2);
        assertThat(outcomes).containsExactly(WebhookMetrics.CREATED, WebhookMetrics.CREATED);
    }

    @Test
    @DisplayName("EP-35 a resolved notice sets resolved_at once and publishes alert.updated once")
    void resolved() {
        webhook.execute(List.of(stale(Status.FIRING)));
        recorded.clear();
        outcomes.clear();

        webhook.execute(List.of(stale(Status.RESOLVED)));
        webhook.execute(List.of(stale(Status.RESOLVED)));

        Alert alert = alerts.alerts.values().iterator().next();
        assertThat(alert.resolvedAt()).isNotNull();
        assertThat(outcomes).containsExactly(WebhookMetrics.RESOLVED, WebhookMetrics.UNKNOWN_RESOLVED);
        assertThat(recorded.events()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("alert.updated");
            assertThat(event.audience()).isEqualTo(Audience.ENGINEERING);
            assertThat(event.data()).containsKey("resolvedAt");
        });
    }

    @Test
    @DisplayName("A resolved notice of an alert that was never received is counted and changes nothing")
    void unknownResolved() {
        webhook.execute(List.of(stale(Status.RESOLVED)));

        assertThat(alerts.alerts).isEmpty();
        assertThat(recorded.events()).isEmpty();
        assertThat(outcomes).containsExactly(WebhookMetrics.UNKNOWN_RESOLVED);
    }

    @Test
    @DisplayName("Notices of one request are one transaction, and the events follow it in the order of the notices")
    void oneTransactionThenEvents() {
        webhook.execute(List.of(
                stale(Status.FIRING),
                stale(Status.RESOLVED),
                notice(Status.FIRING, "aa11", "2026-09-29T21:05:00Z", "ConsumerLagHigh")));

        assertThat(journal)
                .containsExactly(
                        "begin", "commit", "publish alert.created", "publish alert.updated", "publish alert.created");
        assertThat(recorded.events())
                .extracting(UiEvent::key)
                .hasSize(3)
                .first()
                .isEqualTo(recorded.events().get(1).key());
    }

    @Test
    @DisplayName("An empty list of notices is a transaction that does nothing")
    void nothing() {
        webhook.execute(List.of());

        assertThat(recorded.events()).isEmpty();
        assertThat(outcomes).isEmpty();
    }
}
