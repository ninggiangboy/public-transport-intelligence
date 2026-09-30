package dev.pti.analytics.alert.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.pti.analytics.event.domain.InsightEvent;
import dev.pti.common.events.Audience;
import dev.pti.common.events.UiChannel;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The title and body rules of DOC-23 §10.1 and the alert events of §10.3. */
class AlertDomainTest {

    private static final UUID EPISODE = UUID.fromString("c69bcc55-ae25-5b9c-931c-e690e8e5220d");

    @Test
    void theBunchingTitleNamesRouteDirectionAndVehicles() {
        assertThat(AlertTitles.bunching("18", "Northbound", "1234", "1250"))
                .isEqualTo("Bus bunching on route 18 Northbound: vehicles 1234 and 1250");
    }

    @Test
    void theDisruptionTitleNamesRouteAndDirection() {
        assertThat(AlertTitles.disruption("18", "Southbound")).isEqualTo("Delays on route 18 Southbound");
    }

    @Test
    void theTicketingTitleFollowsTheTrigger() {
        assertThat(AlertTitles.ticketingAnomaly(AnomalyTrigger.VOLUME, "Kiosk One", "KIOSK-001"))
                .isEqualTo("Unusual ticket sales at Kiosk One");
        assertThat(AlertTitles.ticketingAnomaly(AnomalyTrigger.REFUND_RATIO, "Kiosk One", "KIOSK-001"))
                .isEqualTo("High refund rate at Kiosk One");
        assertThat(AlertTitles.ticketingAnomaly(AnomalyTrigger.BOTH, "Kiosk One", "KIOSK-001"))
                .isEqualTo("Unusual sales and refund rate at Kiosk One");
    }

    @Test
    void aSalePointWithoutANameIsShownById() {
        assertThat(AlertTitles.ticketingAnomaly(AnomalyTrigger.VOLUME, null, "KIOSK-001"))
                .isEqualTo("Unusual ticket sales at KIOSK-001");
        assertThat(AlertTitles.ticketingAnomaly(AnomalyTrigger.VOLUME, " ", "KIOSK-001"))
                .isEqualTo("Unusual ticket sales at KIOSK-001");
    }

    @Test
    void aLongTitleIsCutToTwoHundredCharactersWithAnEllipsis() {
        String title = AlertTitles.disruption("x".repeat(300), "Northbound");

        assertThat(title).hasSize(200).endsWith("…").startsWith("Delays on route xxx");
    }

    @Test
    void aTitleOfExactlyTwoHundredCharactersIsKept() {
        String label = "y".repeat(200 - "Delays on route  N".length());
        String title = AlertTitles.disruption(label, "N");

        assertThat(title).hasSize(200).doesNotEndWith("…");
    }

    @Test
    void theCutCountsCodePointsNotUtf16Units() {
        String title = AlertTitles.disruption("😀".repeat(300), "N");

        assertThat(title.codePointCount(0, title.length())).isEqualTo(200);
        assertThat(title).endsWith("…");
    }

    @Test
    void anAlertIdAndDedupKeyComeFromTheEpisodeId() {
        AlertDraft draft = AlertDraft.of(
                AlertType.BUNCHING,
                EPISODE,
                1,
                "18",
                AlertTitles.bunching("18", "Northbound", "1234", "1250"),
                Map.of("bunchingId", EPISODE.toString()));

        assertThat(draft.dedupKey()).isEqualTo("bunching:c69bcc55-ae25-5b9c-931c-e690e8e5220d");
        assertThat(draft.id()).isEqualTo(UUID.fromString("fb899421-12d7-5369-ac3f-51d7b885dfcf"));
        assertThat(draft.refTable()).isEqualTo("insight.insight_bus_bunching");
        assertThat(draft.refId()).isEqualTo(EPISODE.toString());
        assertThat(draft.audience()).isEqualTo(Audience.OPERATIONS);
    }

    @Test
    void theAudienceAndReferenceFollowTheTypeTable() {
        assertThat(AlertType.DISRUPTION.audience()).isEqualTo(Audience.PUBLIC);
        assertThat(AlertType.DISRUPTION.refTable()).isEqualTo("insight.insight_service_disruption");
        assertThat(AlertType.DISRUPTION.dedupKey(EPISODE)).startsWith("disruption:");
        assertThat(AlertType.TICKETING_ANOMALY.audience()).isEqualTo(Audience.OPERATIONS);
        assertThat(AlertType.TICKETING_ANOMALY.refTable()).isEqualTo("insight.insight_ticketing_anomaly");
        assertThat(AlertType.TICKETING_ANOMALY.dedupKey(EPISODE)).startsWith("ticketing:");
    }

    @Test
    void aDraftRefusesABadSeverityOrTitle() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AlertDraft.of(AlertType.BUNCHING, EPISODE, 3, "18", "t", Map.of()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AlertDraft.of(AlertType.BUNCHING, EPISODE, 0, "18", "t", Map.of()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AlertDraft.of(AlertType.BUNCHING, EPISODE, 1, "18", " ", Map.of()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AlertDraft.of(AlertType.BUNCHING, EPISODE, 1, "18", "t".repeat(201), Map.of()));
    }

    @Test
    void theCreatedEventCarriesTheWholeAlertToTheAudienceOfTheRow() {
        Instant created = Instant.parse("2026-09-29T21:19:31.020Z");
        AlertRecord record = new AlertRecord(
                UUID.fromString("fb899421-12d7-5369-ac3f-51d7b885dfcf"),
                AlertType.DISRUPTION,
                2,
                Audience.ENGINEERING,
                "18",
                "insight.insight_service_disruption",
                EPISODE.toString(),
                "Delays on route 18 Northbound",
                Map.of("disruptionId", EPISODE.toString()),
                created,
                null);

        InsightEvent event = AlertEvents.created(record, Instant.parse("2026-09-29T21:19:30Z"), created);

        assertThat(event.type()).isEqualTo("alert.created");
        assertThat(event.channel()).isEqualTo(UiChannel.ALERTS);
        assertThat(event.audience()).as("the audience in the row now").isEqualTo(Audience.ENGINEERING);
        assertThat(event.key()).isEqualTo("fb899421-12d7-5369-ac3f-51d7b885dfcf");
        assertThat(event.routeId()).isEqualTo("18");
        assertThat(event.sourceRecordTs()).isEqualTo(Instant.parse("2026-09-29T21:19:30Z"));
        assertThat(event.committedAt()).isEqualTo(created);
        assertThat(event.data())
                .containsEntry("id", "fb899421-12d7-5369-ac3f-51d7b885dfcf")
                .containsEntry("type", "DISRUPTION")
                .containsEntry("severity", 2)
                .containsEntry("audience", "ENGINEERING")
                .containsEntry("routeId", "18")
                .containsEntry("refTable", "insight.insight_service_disruption")
                .containsEntry("refId", EPISODE.toString())
                .containsEntry("title", "Delays on route 18 Northbound")
                .containsEntry("createdAt", created)
                .doesNotContainKey("resolvedAt");
    }

    @Test
    void theUpdatedEventAddsResolvedAtWhenTheAlertIsResolved() {
        Instant created = Instant.parse("2026-09-29T21:19:31Z");
        Instant resolved = Instant.parse("2026-09-29T22:00:00Z");
        AlertRecord record = new AlertRecord(
                UUID.randomUUID(),
                AlertType.BUNCHING,
                1,
                Audience.OPERATIONS,
                null,
                "insight.insight_bus_bunching",
                EPISODE.toString(),
                "t",
                Map.of(),
                created,
                resolved);

        InsightEvent event = AlertEvents.updated(record, null, null);

        assertThat(event.type()).isEqualTo("alert.updated");
        assertThat(event.data()).containsEntry("resolvedAt", resolved).containsEntry("routeId", null);
        assertThat(event.sourceRecordTs()).isNull();
        assertThat(event.committedAt()).isNull();
    }
}
