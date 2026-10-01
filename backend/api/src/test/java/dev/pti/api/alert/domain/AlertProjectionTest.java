package dev.pti.api.alert.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.common.events.Audience;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What an anonymous caller may see of an alert (DOC-32 E-20, DOC-33 §4). */
class AlertProjectionTest {

    @Test
    @DisplayName("A public disruption keeps its allowed body keys and loses the acknowledgement and what triage added")
    void disruption() {
        Alert projected = AlertFixtures.disruption().forAnonymous();

        assertThat(projected.acknowledgedBy()).isNull();
        assertThat(projected.acknowledgedAt()).isNull();
        assertThat(projected.body().keySet())
                .containsExactly(
                        "disruptionId", "directionId", "episodeStart", "currentAvgDelaySeconds", "affectedStopIds");
        assertThat(projected.title()).isEqualTo("Delays on route 18 northbound");
        assertThat(projected.link()).isEqualTo("/map?route=18&disruption=" + AlertFixtures.EPISODE);
    }

    @Test
    @DisplayName("The resolution stays: a passenger learns that the disruption is over")
    void resolvedAtStays() {
        Alert resolved = new Alert(
                AlertFixtures.disruption().id(),
                AlertType.DISRUPTION,
                1,
                Audience.PUBLIC,
                "18",
                null,
                null,
                "title",
                Map.of(),
                AlertFixtures.CREATED,
                null,
                null,
                AlertFixtures.CREATED.plusSeconds(600));

        assertThat(resolved.forAnonymous().resolvedAt()).isEqualTo(AlertFixtures.CREATED.plusSeconds(600));
    }

    @Test
    @DisplayName("A type without a list of public keys keeps an empty body")
    void unknownTypeKeepsNothing() {
        Alert alert = AlertFixtures.of(
                AlertType.BUNCHING, Audience.PUBLIC, "18", Map.of("vehicleLeader", "1187", "gapSeconds", 96));

        assertThat(alert.forAnonymous().body()).isEmpty();
    }
}
