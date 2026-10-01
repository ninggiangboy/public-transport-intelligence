package dev.pti.api.alert.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.common.events.Audience;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The link of each type of alert (DOC-32 E-20, EP-39). */
class AlertLinksTest {

    private static Alert withRef(AlertType type, String routeId, String refId) {
        return new Alert(
                UUID.randomUUID(),
                type,
                1,
                Audience.OPERATIONS,
                routeId,
                "insight.some_table",
                refId,
                "title",
                Map.of(),
                AlertFixtures.CREATED,
                null,
                null,
                null);
    }

    @Test
    @DisplayName("EP-39 a disruption and a bunching go to the map of their route, a ticketing anomaly to its screen")
    void screensOfTheAnalytics() {
        assertThat(AlertLinks.of(withRef(AlertType.DISRUPTION, "18", "d-1"))).isEqualTo("/map?route=18&disruption=d-1");
        assertThat(AlertLinks.of(withRef(AlertType.BUNCHING, "18", "b-1"))).isEqualTo("/map?route=18&bunching=b-1");
        assertThat(AlertLinks.of(withRef(AlertType.TICKETING_ANOMALY, null, "a-1")))
                .isEqualTo("/ops/ticketing?anomaly=a-1");
    }

    @Test
    @DisplayName("An alert without a route or without its episode still gets a link that makes sense")
    void missingParts() {
        assertThat(AlertLinks.of(withRef(AlertType.BUNCHING, "18", null))).isEqualTo("/map?route=18");
        assertThat(AlertLinks.of(withRef(AlertType.DISRUPTION, null, "d-1"))).isEqualTo("/map?disruption=d-1");
        assertThat(AlertLinks.of(withRef(AlertType.DISRUPTION, null, null))).isEqualTo("/map");
        assertThat(AlertLinks.of(withRef(AlertType.TICKETING_ANOMALY, null, null)))
                .isEqualTo("/ops/ticketing");
    }

    @Test
    @DisplayName("EP-39 dead letters and a stale feed go to the operations screens")
    void operationsScreens() {
        assertThat(AlertLinks.of(AlertFixtures.of(AlertType.DLQ_SEVERE, Audience.ENGINEERING, null, Map.of())))
                .isEqualTo("/ops/dlq?severity=2&status=NEW,MANUAL,PENDING_CONFIRM");
        assertThat(AlertLinks.of(AlertFixtures.of(AlertType.FEED_STALE, Audience.ENGINEERING, null, Map.of())))
                .isEqualTo("/ops/jobs?kind=STREAM");
    }

    @Test
    @DisplayName(
            "EP-39 an infrastructure alert goes to its runbook when it has an absolute one, else to the jobs screen")
    void infraRunbook() {
        Alert withRunbook = AlertFixtures.of(
                AlertType.INFRA,
                Audience.ENGINEERING,
                null,
                Map.of("annotations", Map.of("runbook_url", "https://runbooks.example/consumer-lag")));
        Alert without = AlertFixtures.of(
                AlertType.INFRA, Audience.ENGINEERING, null, Map.of("annotations", Map.of("summary", "s")));
        Alert relative = AlertFixtures.of(
                AlertType.INFRA,
                Audience.ENGINEERING,
                null,
                Map.of("annotations", Map.of("runbook_url", "/docs/runbook")));
        Alert scripted = AlertFixtures.of(
                AlertType.INFRA,
                Audience.ENGINEERING,
                null,
                Map.of("annotations", Map.of("runbook_url", "javascript:alert(1)")));
        Alert noAnnotations = AlertFixtures.of(AlertType.INFRA, Audience.ENGINEERING, null, Map.of());

        assertThat(AlertLinks.of(withRunbook)).isEqualTo("https://runbooks.example/consumer-lag");
        assertThat(AlertLinks.of(without)).isEqualTo("/ops/jobs");
        assertThat(AlertLinks.of(relative)).isEqualTo("/ops/jobs");
        assertThat(AlertLinks.of(scripted)).isEqualTo("/ops/jobs");
        assertThat(AlertLinks.of(noAnnotations)).isEqualTo("/ops/jobs");
    }
}
