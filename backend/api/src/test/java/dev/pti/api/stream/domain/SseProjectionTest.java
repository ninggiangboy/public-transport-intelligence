package dev.pti.api.stream.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The public projection of DOC-33 §4 and the alert link of §5.5. */
class SseProjectionTest {

    @Test
    @DisplayName("SE-03 disruption.opened for anonymous callers drops the baseline and the z-score")
    void disruptionIsProjected() {
        Map<String, Object> data = Map.of(
                "id",
                "d1",
                "routeId",
                "18",
                "directionId",
                0,
                "episodeStart",
                "2026-09-29T20:58:00Z",
                "currentAvgDelaySeconds",
                212.7,
                "baselineMeanSeconds",
                61.4,
                "zScore",
                3.98,
                "affectedStopIds",
                List.of("51418"));

        Map<String, Object> projected =
                SseProjection.forAnonymous("disruption.opened", data).orElseThrow();

        assertThat(projected)
                .containsKeys(
                        "id", "routeId", "directionId", "episodeStart", "currentAvgDelaySeconds", "affectedStopIds")
                .doesNotContainKeys("baselineMeanSeconds", "zScore");
    }

    @Test
    @DisplayName("SE-04 and SE-05: bunching and any type without a public view are not sent to anonymous callers")
    void noPublicView() {
        assertThat(SseProjection.forAnonymous("bunching.opened", Map.of("id", "b")))
                .isEmpty();
        assertThat(SseProjection.forAnonymous("weather.changed", Map.of("id", "w")))
                .isEmpty();
        assertThat(SseProjection.hasPublicView("weather.changed")).isFalse();
        assertThat(SseProjection.hasPublicView("vehicles.batch")).isTrue();
    }

    @Test
    @DisplayName("vehicles.batch is sent whole to anonymous callers")
    void vehiclesAreWhole() {
        Map<String, Object> data = Map.of("routeId", "18", "vehicles", List.of(Map.of("vehicleId", "1")));

        assertThat(SseProjection.forAnonymous("vehicles.batch", data)).contains(data);
    }

    @Test
    @DisplayName("An alert for anonymous callers loses its acknowledgement and the body keys not allowed for its type")
    void alertForAnonymous() {
        Map<String, Object> data = Map.of(
                "id", "a1",
                "type", "DISRUPTION",
                "routeId", "18",
                "refId", "d1",
                "acknowledgedBy", "user:operator",
                "acknowledgedAt", "2026-09-29T21:00:00Z",
                "body", Map.of("disruptionId", "d1", "likelyCause", "detour", "peakZScore", 4.4));

        Map<String, Object> projected =
                SseProjection.forAnonymous("alert.updated", data).orElseThrow();

        assertThat(projected).doesNotContainKeys("acknowledgedBy", "acknowledgedAt");
        assertThat(projected.get("body")).isEqualTo(Map.of("disruptionId", "d1"));
        assertThat(projected.get("link")).isEqualTo("/map?route=18&disruption=d1");
    }

    @Test
    @DisplayName("Viewers get the whole alert and its link when the publisher (analytics) did not add one")
    void alertForViewer() {
        Map<String, Object> data = Map.of("id", "a1", "type", "BUNCHING", "routeId", "18", "refId", "b1");

        assertThat(SseProjection.forViewer("alert.created", data))
                .containsEntry("link", "/map?route=18&bunching=b1")
                .containsEntry("id", "a1");
        Map<String, Object> linked = Map.of("type", "BUNCHING", "link", "/given");
        assertThat(SseProjection.forViewer("alert.created", linked)).isSameAs(linked);
    }

    @Test
    @DisplayName("alert.retracted keeps only the id and the route for anonymous callers")
    void retracted() {
        assertThat(SseProjection.forAnonymous("alert.retracted", Map.of("id", "a1", "routeId", "18", "x", 1)))
                .contains(Map.of("id", "a1", "routeId", "18"));
    }
}
