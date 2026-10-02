package dev.pti.api.testing;

import dev.pti.api.stream.domain.HubEvent;
import dev.pti.common.events.Audience;
import dev.pti.common.events.UiChannel;
import dev.pti.common.events.UiEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Hub events as the consumer would hand them over, with real ULIDs of a chosen time. */
public final class StreamFixtures {

    private StreamFixtures() {}

    /** A ULID whose time is {@code at}: the publishers make them the same way (DOC-33 §2.1). */
    public static String ulid(Instant at) {
        String alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
        long millis = at.toEpochMilli();
        char[] time = new char[10];
        for (int i = 9; i >= 0; i--) {
            time[i] = alphabet.charAt((int) (millis & 31));
            millis >>>= 5;
        }
        String random = UiEvent.of(at, "x", UiChannel.ALERTS, Audience.PUBLIC, "k", null, null, Map.of())
                .id()
                .substring(10);
        return new String(time) + random;
    }

    public static HubEvent alert(Instant at, Audience audience, @Nullable String routeId) {
        return HubEvent.received(
                ulid(at),
                "alert.created",
                UiChannel.ALERTS,
                audience,
                at,
                null,
                routeId,
                Map.of("id", "a-" + at.toEpochMilli(), "type", "DISRUPTION", "title", "Delays", "body", Map.of()));
    }

    public static HubEvent job(Instant at) {
        return HubEvent.received(
                ulid(at), "job.run", UiChannel.JOBS, Audience.ENGINEERING, at, null, null, Map.of("runId", "job:1"));
    }

    public static HubEvent vehicles(Instant at, String routeId, String vehicleId, Instant position) {
        return HubEvent.received(
                ulid(at),
                "vehicles.batch",
                UiChannel.VEHICLES,
                Audience.PUBLIC,
                at,
                position,
                routeId,
                Map.of(
                        "routeId",
                        routeId,
                        "vehicles",
                        List.of(Map.of("vehicleId", vehicleId, "eventTimestamp", position.toString()))));
    }
}
