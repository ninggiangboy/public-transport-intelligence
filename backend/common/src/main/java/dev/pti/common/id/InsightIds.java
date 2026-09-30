package dev.pti.common.id;

import com.github.f4b6a3.uuid.UuidCreator;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Deterministic ids for analytics output (DR-29, DOC-23 §2.3). The same natural key gives the same id across
 * replays, in every app that computes it. Plain Java apart from the UUID library.
 */
public final class InsightIds {

    /** UUIDv5(NAMESPACE_URL, "urn:pti:insight"). Never change: every stored id depends on it. */
    public static final UUID NAMESPACE = UUID.fromString("de46bd73-4eee-54a7-89f5-4aab7f289ad1");

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private InsightIds() {}

    public static UUID bunching(String routeId, String leader, String follower, Instant episodeStart) {
        return v5("bunching|" + routeId + "|" + leader + "|" + follower + "|" + TS.format(episodeStart));
    }

    public static UUID disruption(String routeId, int directionId, Instant episodeStart) {
        return v5("disruption|" + routeId + "|" + directionId + "|" + TS.format(episodeStart));
    }

    public static UUID ticketingAnomaly(String salePointId, Instant windowStart) {
        return v5("ticketing|" + salePointId + "|" + TS.format(windowStart));
    }

    /** DOC-24. */
    public static UUID dispatchSuggestion(UUID bunchingId) {
        return v5("dispatch|" + bunchingId);
    }

    public static UUID alert(String dedupKey) {
        return v5("alert|" + dedupKey);
    }

    private static UUID v5(String name) {
        // The name is hashed as its UTF-8 bytes.
        return UuidCreator.getNameBasedSha1(NAMESPACE, name);
    }
}
