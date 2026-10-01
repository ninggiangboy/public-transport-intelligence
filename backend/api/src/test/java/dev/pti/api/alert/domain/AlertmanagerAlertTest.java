package dev.pti.api.alert.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.common.events.Audience;
import dev.pti.common.id.InsightIds;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** How a notice of Alertmanager becomes a row of the alert feed (DOC-32 E-80, EP-36, DOC-28). */
class AlertmanagerAlertTest {

    private static AlertmanagerAlert notice(Map<String, String> labels, Map<String, String> annotations) {
        return new AlertmanagerAlert(
                AlertmanagerAlert.Status.FIRING,
                "9f8e7d6c5b4a3210",
                "2026-09-29T21:00:00.000Z",
                labels,
                annotations,
                "http://prometheus:9090/graph?g0.expr=up");
    }

    @Test
    @DisplayName("EP-36 GtfsRtFeedStale is FEED_STALE for engineering, critical is severity 2")
    void feedStale() {
        NewAlert alert = notice(
                        Map.of("alertname", "GtfsRtFeedStale", "severity", "critical", "source", "vehicle_positions"),
                        Map.of("summary", "GTFS-rt vehicle positions stopped for 2 minutes"))
                .toNewAlert();

        assertThat(alert.type()).isEqualTo(AlertType.FEED_STALE);
        assertThat(alert.audience()).isEqualTo(Audience.ENGINEERING);
        assertThat(alert.severity()).isEqualTo(2);
        assertThat(alert.title()).isEqualTo("GTFS-rt vehicle positions stopped for 2 minutes");
        assertThat(alert.routeId()).isNull();
    }

    @ParameterizedTest(name = "{0} is {1}")
    @CsvSource({
        "GtfsRtFeedStale, FEED_STALE",
        "DlqSevereRecords, DLQ_SEVERE",
        "DlqUpstreamErrorBurst, DLQ_SEVERE",
        "DlqNeedsAttention, INFRA",
        "ConsumerLagHigh, INFRA",
        "TargetDown, INFRA"
    })
    @DisplayName("The alert name decides the type; DlqNeedsAttention and everything else is INFRA")
    void typeByName(String name, AlertType expected) {
        assertThat(notice(Map.of("alertname", name), Map.of()).toNewAlert().type())
                .isEqualTo(expected);
    }

    @ParameterizedTest(name = "severity {0} is {1}")
    @CsvSource({"critical, 2", "warning, 1", "info, 0", "page, 1"})
    @DisplayName("The severity label is mapped to 2, 1 or 0, and an unknown one counts as a warning")
    void severity(String label, int expected) {
        assertThat(notice(Map.of("alertname", "X", "severity", label), Map.of())
                        .toNewAlert()
                        .severity())
                .isEqualTo(expected);
        assertThat(notice(Map.of("alertname", "X"), Map.of()).toNewAlert().severity())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("The dedup key is am:<fingerprint>:<startsAt>, and the id is the UUIDv5 of it, as for analytics")
    void dedupKeyAndId() {
        AlertmanagerAlert notice = notice(Map.of("alertname", "X"), Map.of());

        assertThat(notice.dedupKey()).isEqualTo("am:9f8e7d6c5b4a3210:2026-09-29T21:00:00.000Z");
        assertThat(notice.toNewAlert().id()).isEqualTo(InsightIds.alert(notice.dedupKey()));
        assertThat(notice.toNewAlert().dedupKey()).isEqualTo(notice.dedupKey());
    }

    @Test
    @DisplayName("The route comes from the label route_id")
    void route() {
        assertThat(notice(Map.of("alertname", "X", "route_id", "18"), Map.of())
                        .toNewAlert()
                        .routeId())
                .isEqualTo("18");
    }

    @Test
    @DisplayName("The title is the summary cut to 200 characters, or the alert name when there is none")
    void title() {
        String longSummary = "x".repeat(250);

        assertThat(notice(Map.of("alertname", "X"), Map.of("summary", longSummary))
                        .toNewAlert()
                        .title())
                .hasSize(200);
        assertThat(notice(Map.of("alertname", "ConsumerLagHigh"), Map.of())
                        .toNewAlert()
                        .title())
                .isEqualTo("ConsumerLagHigh");
        assertThat(notice(Map.of("alertname", "ConsumerLagHigh"), Map.of("summary", "  "))
                        .toNewAlert()
                        .title())
                .isEqualTo("ConsumerLagHigh");
    }

    @Test
    @DisplayName("The body holds the alert name, labels, annotations, start and generator URL")
    void body() {
        NewAlert alert = notice(Map.of("alertname", "X"), Map.of("runbook_url", "https://r.example/x"))
                .toNewAlert();

        assertThat(alert.body())
                .containsEntry("alertname", "X")
                .containsEntry("labels", Map.of("alertname", "X"))
                .containsEntry("annotations", Map.of("runbook_url", "https://r.example/x"))
                .containsEntry("startsAt", "2026-09-29T21:00:00.000Z")
                .containsEntry("generatorURL", "http://prometheus:9090/graph?g0.expr=up");
    }

    @Test
    @DisplayName("Without a generator URL the body has no such key")
    void bodyWithoutGenerator() {
        AlertmanagerAlert notice = new AlertmanagerAlert(
                AlertmanagerAlert.Status.FIRING, "f", "2026-09-29T21:00:00Z", Map.of("alertname", "X"), Map.of(), null);

        assertThat(notice.toNewAlert().body()).doesNotContainKey("generatorURL");
    }
}
