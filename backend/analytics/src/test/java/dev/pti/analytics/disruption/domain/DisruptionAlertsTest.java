package dev.pti.analytics.disruption.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.analytics.alert.domain.AlertDraft;
import dev.pti.analytics.event.domain.InsightEvent;
import dev.pti.analytics.reference.domain.RouteInfo;
import dev.pti.common.events.Audience;
import dev.pti.common.id.InsightIds;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The alert and the UI events of a disruption episode (DOC-23 §10.1, §10.3). */
class DisruptionAlertsTest {

    private static final Instant START = Instant.parse("2026-09-29T20:58:00Z");
    private static final UUID ID = InsightIds.disruption("18", 0, START);

    private final DisruptionAlerts alerts = new DisruptionAlerts(4.0);
    private final RouteInfo route = new RouteInfo("18", 3, "18", Map.of(0, "Northbound"));

    private static DisruptionEpisode episode(double peakZ, @Nullable CloseReason reason) {
        Instant end = reason == null ? null : START.plusSeconds(1800);
        return new DisruptionEpisode(
                ID, "18", 0, START, end, reason, 61.4, 29.8, 212.7, 3.98, 240.0, peakZ, 12, List.of("51418"), START);
    }

    @Test
    void severityIsOneBelowTheHighZAndTwoFromIt() {
        assertThat(alerts.severity(episode(3.99, null))).isEqualTo(1);
        assertThat(alerts.severity(episode(4.00, null))).isEqualTo(2);
        assertThat(alerts.severity(episode(9999.99, null))).isEqualTo(2);
    }

    @Test
    void theDraftIsAPublicDisruptionAlertThatPointsAtTheEpisode() {
        AlertDraft draft = alerts.draft(episode(3.98, null), route);

        assertThat(draft.audience()).isEqualTo(Audience.PUBLIC);
        assertThat(draft.severity()).isEqualTo(1);
        assertThat(draft.dedupKey()).isEqualTo("disruption:" + ID);
        assertThat(draft.id()).isEqualTo(InsightIds.alert("disruption:" + ID));
        assertThat(draft.refTable()).isEqualTo("insight.insight_service_disruption");
        assertThat(draft.refId()).isEqualTo(ID.toString());
        assertThat(draft.routeId()).isEqualTo("18");
        assertThat(draft.title()).isEqualTo("Delays on route 18 Northbound");
        assertThat(draft.body())
                .containsEntry("disruptionId", ID.toString())
                .containsEntry("directionId", 0)
                .containsEntry("episodeStart", START)
                .containsEntry("currentAvgDelaySeconds", 212.7)
                .containsEntry("baselineMeanSeconds", 61.4)
                .containsEntry("zScore", 3.98)
                .containsEntry("affectedStopIds", List.of("51418"));
    }

    @Test
    void anEpisodeThatOpensWithAHighPeakGetsAnAlertOfSeverityTwo() {
        assertThat(alerts.draft(episode(4.5, null), route).severity()).isEqualTo(2);
    }

    @Test
    void theRaiseAndClosePatchesFollowSection10() {
        DisruptionEpisode closed = episode(4.57, CloseReason.RECOVERED);

        assertThat(alerts.raisePatch(closed)).containsOnly(Map.entry("peakZScore", 4.57));
        assertThat(alerts.closePatch(closed))
                .containsEntry("episodeEnd", START.plusSeconds(1800))
                .containsEntry("closeReason", "RECOVERED")
                .containsEntry("peakZScore", 4.57)
                .hasSize(3);
    }

    @Test
    void theEventsCarryTheFieldsOfSection103AndTheGivenAudience() {
        InsightEvent opened = alerts.openedEvent(
                episode(3.98, null), Audience.PUBLIC, Instant.parse("2026-09-29T20:59:00Z"), START.plusSeconds(5));
        InsightEvent closed =
                alerts.closedEvent(episode(4.57, CloseReason.MAX_DURATION), Audience.ENGINEERING, null, null);

        assertThat(opened.type()).isEqualTo("disruption.opened");
        assertThat(opened.audience()).isEqualTo(Audience.PUBLIC);
        assertThat(opened.key()).isEqualTo(ID.toString());
        assertThat(opened.routeId()).isEqualTo("18");
        assertThat(opened.sourceRecordTs()).isEqualTo(Instant.parse("2026-09-29T20:59:00Z"));
        assertThat(opened.data())
                .containsOnlyKeys(
                        "id",
                        "routeId",
                        "directionId",
                        "episodeStart",
                        "currentAvgDelaySeconds",
                        "baselineMeanSeconds",
                        "zScore",
                        "affectedStopIds");
        assertThat(closed.type()).isEqualTo("disruption.closed");
        assertThat(closed.audience()).isEqualTo(Audience.ENGINEERING);
        assertThat(closed.data())
                .containsOnlyKeys("id", "routeId", "directionId", "episodeEnd", "closeReason", "peakZScore")
                .containsEntry("closeReason", "MAX_DURATION");
    }
}
