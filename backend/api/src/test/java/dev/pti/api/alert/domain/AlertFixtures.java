package dev.pti.api.alert.domain;

import dev.pti.common.events.Audience;
import dev.pti.common.id.InsightIds;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Alerts as the tests need them: a disruption, a bunching, an Alertmanager one. */
public final class AlertFixtures {

    public static final Instant CREATED = Instant.parse("2026-09-29T20:59:31Z");
    public static final String EPISODE = "9d8e7f6a-5b4c-5d3e-8f2a-1b0c9d8e7f6a";

    private AlertFixtures() {}

    /** The public alert of a disruption episode, with a body that holds keys a passenger may and may not see. */
    public static Alert disruption() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("disruptionId", EPISODE);
        body.put("directionId", 0);
        body.put("episodeStart", "2026-09-29T20:58:00Z");
        body.put("currentAvgDelaySeconds", 212.7);
        body.put("baselineMeanSeconds", 61.4);
        body.put("zScore", 3.98);
        body.put("affectedStopIds", java.util.List.of("51418", "51420"));
        body.put("likelyCause", "traffic");
        return new Alert(
                id("disruption:" + EPISODE),
                AlertType.DISRUPTION,
                1,
                Audience.PUBLIC,
                "18",
                "insight.insight_service_disruption",
                EPISODE,
                "Delays on route 18 northbound",
                body,
                CREATED,
                "user:operator",
                CREATED.plusSeconds(159),
                null);
    }

    public static Alert bunching(Instant createdAt) {
        String episode = "6f1c2a9e-4b1d-5c8e-9a2f-3d4e5f6a7b8c";
        return new Alert(
                id("bunching:" + episode),
                AlertType.BUNCHING,
                1,
                Audience.OPERATIONS,
                "18",
                "insight.insight_bus_bunching",
                episode,
                "Bus bunching on route 18 Northbound: vehicles 1187 and 1203",
                Map.of("bunchingId", episode, "directionId", 0),
                createdAt,
                null,
                null,
                null);
    }

    public static Alert of(AlertType type, Audience audience, @Nullable String routeId, Map<String, Object> body) {
        return new Alert(
                UUID.randomUUID(),
                type,
                1,
                audience,
                routeId,
                null,
                null,
                "An alert of type " + type,
                body,
                CREATED,
                null,
                null,
                null);
    }

    public static UUID id(String dedupKey) {
        return InsightIds.alert(dedupKey);
    }
}
