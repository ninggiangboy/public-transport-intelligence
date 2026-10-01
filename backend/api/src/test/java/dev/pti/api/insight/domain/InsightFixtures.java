package dev.pti.api.insight.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Insight rows as the tests need them, from the examples of DOC-32 §4. */
public final class InsightFixtures {

    public static final UUID BUNCHING_ID = UUID.fromString("6f1c2a9e-4b1d-5c8e-9a2f-3d4e5f6a7b8c");
    public static final UUID SUGGESTION_ID = UUID.fromString("3b2a1c0d-9e8f-4a7b-8c6d-5e4f3a2b1c0d");
    public static final UUID DISRUPTION_ID = UUID.fromString("9d8e7f6a-5b4c-5d3e-8f2a-1b0c9d8e7f6a");
    public static final UUID ANOMALY_ID = UUID.fromString("c1d2e3f4-a5b6-5c7d-8e9f-0a1b2c3d4e5f");
    public static final UUID BATCH_ID = UUID.fromString("0b9d3c52-6a1f-4c1e-9f0a-2a7d1e4c8b10");

    /** Inside the default range of a request made at {@link #NOW}. */
    public static final Instant NOW = Instant.parse("2026-09-29T21:19:35Z");

    private InsightFixtures() {}

    public static BunchingEpisode openBunching(
            UUID id, String route, Instant start, @Nullable SuggestionRef suggestion) {
        return new BunchingEpisode(
                id,
                route,
                0,
                "1187",
                "1203",
                "t-2041",
                "t-2043",
                start,
                null,
                "OPEN",
                null,
                600,
                300,
                96,
                112,
                "51418",
                29,
                Instant.parse("2026-09-29T21:19:30Z"),
                "DONE",
                BATCH_ID,
                suggestion);
    }

    public static BunchingEpisode closedBunching(UUID id, String route, Instant start, Instant end) {
        return new BunchingEpisode(
                id,
                route,
                0,
                "1187",
                "1203",
                "t-2041",
                "t-2043",
                start,
                end,
                "CLOSED",
                "GAP_RECOVERED",
                600,
                300,
                96,
                341,
                "51418",
                78,
                end,
                "PENDING",
                BATCH_ID,
                null);
    }

    public static DispatchSuggestion suggestion(
            UUID id, UUID bunchingId, String route, Instant createdAt, String confidence) {
        return new DispatchSuggestion(
                id,
                bunchingId,
                route,
                "hold_follower",
                new BigDecimal(confidence),
                "jev@0.2.0",
                createdAt,
                "{\"task\": \"Suggest one dispatch action.\", \"episode\": {\"gapSeconds\": 118}}",
                null,
                null,
                null);
    }

    public static DisruptionEpisode disruption(UUID id, String route, Instant start, String audience, boolean detail) {
        return new DisruptionEpisode(
                id,
                route,
                0,
                start,
                null,
                "OPEN",
                1,
                audience,
                new BigDecimal("61.4"),
                new BigDecimal("38.0"),
                new BigDecimal("212.7"),
                new BigDecimal("3.98"),
                new BigDecimal("230.1"),
                new BigDecimal("4.44"),
                57,
                List.of("51418", "51420", "51422"),
                Instant.parse("2026-09-29T21:19:00Z"),
                "DONE",
                new BigDecimal("0.120"),
                "traffic",
                new BigDecimal("0.710"),
                "jev@0.2.0",
                detail ? "RECOVERED" : null,
                detail ? BATCH_ID : null,
                detail ? Instant.parse("2026-09-29T21:05:00Z") : null);
    }

    public static TicketingAnomaly anomaly(
            UUID id, String salePoint, Instant detectedAt, @Nullable String category, @Nullable Integer severity) {
        return new TicketingAnomaly(
                id,
                salePoint,
                "Nicollet Mall Station kiosk 2",
                "18",
                detectedAt.minusSeconds(900),
                detectedAt,
                detectedAt,
                "REFUND_RATIO",
                25,
                12,
                new BigDecimal("0.4800"),
                new BigDecimal("50.00"),
                new BigDecimal("14.20"),
                new BigDecimal("3.10"),
                new BigDecimal("3.48"),
                category == null ? "PENDING" : "DONE",
                category,
                category == null ? null : new BigDecimal("0.770"),
                severity,
                severity == null ? null : new BigDecimal("0.690"),
                category == null ? null : "jev@0.2.0",
                "{\"txnCount\": 25, \"refundCount\": 12}",
                BATCH_ID);
    }
}
