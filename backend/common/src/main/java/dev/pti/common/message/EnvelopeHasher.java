package dev.pti.common.message;

/**
 * The payload hash of an envelope (DR-04, DOC-09 §9), as a port for code that must not see Jackson. The
 * implementation, {@code dev.pti.common.json.JsonEnvelopeHasher}, hashes the RFC 8785 canonical JSON.
 */
public interface EnvelopeHasher {

    /** SHA-256, lower-case hex, of the fields that identify the data (not {@code message_id} or {@code produced_at}). */
    String hash(Envelope<?> envelope);
}
