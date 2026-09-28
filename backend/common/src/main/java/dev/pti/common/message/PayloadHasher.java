package dev.pti.common.message;

import dev.pti.common.json.MessageJson;
import dev.pti.common.time.Timestamps;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.erdtman.jcs.JsonCanonicalizer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Payload hash for deduplication (DR-04, DOC-09 §9): SHA-256, lower-case hex, of the RFC 8785 canonical JSON of
 * {@code {schema_version, entity_type, event_timestamp, payload}}. {@code message_id}, {@code produced_at} and
 * {@code source} are left out, so a resend of the same data hashes the same.
 */
public final class PayloadHasher {

    /** Fields of an unwrapped CDC value that are not part of the data (DOC-09 §9). */
    private static final List<String> CDC_EXCLUDED = List.of("__lsn", "__source_ts_ms", "customer_ref");

    private PayloadHasher() {}

    public static String hash(Envelope<?> envelope) {
        JsonNode tree = MessageJson.mapper().valueToTree(envelope);
        return hash(tree);
    }

    /**
     * Hashes a parsed envelope as received. {@code event_timestamp} is normalized to millisecond precision.
     *
     * @throws IllegalArgumentException when a hashed field is missing or the timestamp is not RFC 3339
     */
    public static String hash(JsonNode envelope) {
        ObjectNode hashed = MessageJson.mapper().createObjectNode();
        hashed.set("schema_version", required(envelope, "schema_version"));
        hashed.set("entity_type", required(envelope, "entity_type"));
        hashed.put(
                "event_timestamp",
                Timestamps.normalize(required(envelope, "event_timestamp").asString()));
        hashed.set("payload", required(envelope, "payload"));
        return sha256(canonical(hashed));
    }

    /** Hashes an unwrapped Debezium value without {@code __lsn}, {@code __source_ts_ms} and {@code customer_ref}. */
    public static String hashCdc(ObjectNode value) {
        ObjectNode hashed = value.deepCopy();
        hashed.remove(CDC_EXCLUDED);
        return sha256(canonical(hashed));
    }

    private static JsonNode required(JsonNode envelope, String field) {
        JsonNode value = envelope.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException(field + " is required for the payload hash");
        }
        return value;
    }

    private static String canonical(JsonNode node) {
        try {
            return new JsonCanonicalizer(MessageJson.mapper().writeValueAsString(node)).getEncodedString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
