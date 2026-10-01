package dev.pti.api.alert.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.api.alert.domain.Alert;
import dev.pti.api.alert.domain.AlertFixtures;
import dev.pti.api.platform.domain.NotFoundException;
import dev.pti.apitest.InMemoryAlerts;
import dev.pti.common.events.Audience;
import dev.pti.common.events.UiChannel;
import dev.pti.common.events.UiEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@code POST /alerts/{id}/ack} (DOC-32 E-21, EP-16). */
class AcknowledgeAlertTest extends AlertUseCaseSupport {

    private final List<String> writes = new ArrayList<>();
    private AcknowledgeAlert acknowledge;
    private Alert alert;

    @BeforeEach
    void setUp() {
        alert = AlertFixtures.bunching(Instant.parse("2026-09-29T21:12:31Z"));
        alerts.add(alert);
        acknowledge = new AcknowledgeAlert(
                alerts.store, events, (operation, outcome) -> writes.add(operation + ":" + outcome), clock, tx);
    }

    @Test
    @DisplayName("The first acknowledgement is written, counted as created, and published as alert.updated")
    void firstAcknowledgement() {
        Alert result = acknowledge.execute("user:operator", alert.id());

        assertThat(result.acknowledgedBy()).isEqualTo("user:operator");
        assertThat(result.acknowledgedAt()).isEqualTo(InMemoryAlerts.NOW);
        assertThat(writes).containsExactly("ack:created");
        assertThat(recorded.events()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("alert.updated");
            assertThat(event.channel()).isEqualTo(UiChannel.ALERTS);
            assertThat(event.audience()).isEqualTo(Audience.OPERATIONS);
            assertThat(event.key()).isEqualTo(alert.id().toString());
            assertThat(event.routeId()).isEqualTo("18");
            assertThat(event.occurredAt()).isEqualTo(REAL_NOW);
            assertThat(event.sourceRecordTs()).isNull();
        });
    }

    @Test
    @DisplayName("The event carries the whole alert as GET /alerts gives it, link included")
    void eventData() {
        acknowledge.execute("user:operator", alert.id());

        UiEvent event = recorded.events().getFirst();
        assertThat(event.data())
                .containsEntry("id", alert.id().toString())
                .containsEntry("type", "BUNCHING")
                .containsEntry("severity", 1)
                .containsEntry("audience", "OPERATIONS")
                .containsEntry("routeId", "18")
                .containsEntry("refTable", "insight.insight_bus_bunching")
                .containsEntry("acknowledgedBy", "user:operator")
                .containsEntry("acknowledgedAt", "2026-09-29T21:14:02.123Z")
                .containsEntry("createdAt", "2026-09-29T21:12:31Z")
                .containsEntry("link", alert.link())
                .doesNotContainKey("resolvedAt");
    }

    @Test
    @DisplayName(
            "EP-16 the second acknowledgement is answered with the record as it is: the first operator stays, one event")
    void secondAcknowledgement() {
        acknowledge.execute("user:operator", alert.id());
        Alert again = acknowledge.execute("user:other", alert.id());

        assertThat(again.acknowledgedBy()).isEqualTo("user:operator");
        assertThat(recorded.events()).hasSize(1);
        assertThat(writes).containsExactly("ack:created", "ack:idempotent");
    }

    @Test
    @DisplayName("An alert that is resolved can still be acknowledged")
    void resolvedAlert() {
        alerts.alerts.put(
                alert.id(),
                new Alert(
                        alert.id(),
                        alert.type(),
                        alert.severity(),
                        alert.audience(),
                        alert.routeId(),
                        alert.refTable(),
                        alert.refId(),
                        alert.title(),
                        alert.body(),
                        alert.createdAt(),
                        null,
                        null,
                        Instant.parse("2026-09-29T21:13:00Z")));

        Alert result = acknowledge.execute("user:operator", alert.id());

        assertThat(result.acknowledgedBy()).isEqualTo("user:operator");
        assertThat(result.resolvedAt()).isEqualTo(Instant.parse("2026-09-29T21:13:00Z"));
        assertThat(recorded.events().getFirst().data()).containsEntry("resolvedAt", "2026-09-29T21:13:00Z");
    }

    @Test
    @DisplayName("An unknown alert is a 404 and nothing is published")
    void unknownAlert() {
        assertThatThrownBy(() -> acknowledge.execute("user:operator", UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
        assertThat(recorded.events()).isEmpty();
    }

    @Test
    @DisplayName("DOC-49 §5.2 the event is published after the transaction has returned, never inside it")
    void publishedAfterCommit() {
        acknowledge.execute("user:operator", alert.id());

        assertThat(journal).containsExactly("begin", "commit", "publish alert.updated");
    }

    @Test
    @DisplayName("A write that fails rolls back and publishes nothing")
    void failureIsNotPublished() {
        alerts.reset();

        assertThatThrownBy(() -> acknowledge.execute("user:operator", alert.id()))
                .isInstanceOf(NotFoundException.class);
        assertThat(journal).doesNotContain("commit");
        assertThat(writes).isEmpty();
    }
}
