package dev.pti.simulator.ledger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One row of {@code sim.sim_message_ledger} (DOC-13 §6, DOC-25 §6.4): what the simulator sent, written only after the
 * broker acknowledged it (DR-28).
 *
 * @param producedAt real time, like the envelope's {@code produced_at} (DOC-25 §3.1)
 * @param payloadHash {@code null} for deliberately malformed JSON
 * @param invalidKind set by the {@code bad-data} scenario (P3)
 * @param resendOf set by the {@code duplicates} scenario (P3)
 */
public record LedgerEntry(
        UUID messageId,
        String entityType,
        List<String> businessKeys,
        Instant eventTimestamp,
        Instant producedAt,
        int schemaVersion,
        @Nullable String payloadHash,
        @Nullable String invalidKind,
        @Nullable UUID resendOf,
        @Nullable UUID scenarioRunId) {

    public LedgerEntry {
        businessKeys = List.copyOf(businessKeys);
    }

    /** The same message, marked as produced or affected by a scenario run (DOC-25 §7.1). */
    public LedgerEntry withScenarioRun(UUID runId) {
        return new LedgerEntry(
                messageId,
                entityType,
                businessKeys,
                eventTimestamp,
                producedAt,
                schemaVersion,
                payloadHash,
                invalidKind,
                resendOf,
                runId);
    }

    /**
     * The entry of a message corrupted by {@code bad-data}: the business keys stay those of the original message
     * (DOC-25 §7.4).
     *
     * @param hash the hash of the corrupted message; {@code null} when it is not valid JSON
     */
    public LedgerEntry corrupted(String kind, int version, @Nullable String hash, UUID runId) {
        return new LedgerEntry(
                messageId, entityType, businessKeys, eventTimestamp, producedAt, version, hash, kind, null, runId);
    }

    /** The entry of a resend by {@code duplicates}: new id and send time, same keys and hash (DOC-25 §7.5). */
    public LedgerEntry resend(UUID newMessageId, Instant newProducedAt, UUID runId) {
        return new LedgerEntry(
                newMessageId,
                entityType,
                businessKeys,
                eventTimestamp,
                newProducedAt,
                schemaVersion,
                payloadHash,
                null,
                messageId,
                runId);
    }
}
