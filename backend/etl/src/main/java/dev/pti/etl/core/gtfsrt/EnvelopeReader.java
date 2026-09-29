package dev.pti.etl.core.gtfsrt;

import dev.pti.common.error.DeserializationException;
import dev.pti.common.error.SchemaViolationException;
import dev.pti.common.json.MessageJson;
import dev.pti.common.message.EntityType;
import dev.pti.common.message.Envelope;
import dev.pti.common.message.MessageSchemas;
import dev.pti.common.message.Payload;
import dev.pti.common.message.PayloadHasher;
import dev.pti.common.message.PayloadType;
import dev.pti.etl.core.InboundMessage;
import dev.pti.etl.core.Utf8;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Turns the bytes of a GTFS-realtime record into a validated envelope (DOC-20 §4.1): UTF-8, JSON, envelope type
 * fields ({@code DESERIALIZE}), then the JSON Schema of the {@code (entity_type, schema_version)} pair, DTO binding
 * and Bean Validation ({@code SCHEMA}, DQ-01).
 */
public final class EnvelopeReader {

    /** A message that passed DQ-01. */
    public record Read(JsonNode tree, Envelope<? extends Payload> envelope, String hash) {}

    private final MessageSchemas schemas;
    private final Validator validator;

    public EnvelopeReader(MessageSchemas schemas, Validator validator) {
        this.schemas = schemas;
        this.validator = validator;
    }

    public Read read(InboundMessage message, EntityType expected) {
        JsonNode tree = tree(Objects.requireNonNull(message.value(), "tombstone"));
        JsonNode entity = tree.path("entity_type");
        JsonNode version = tree.path("schema_version");
        if (!entity.isString() || !version.isIntegralNumber()) {
            throw new DeserializationException("Envelope has no entity_type or schema_version", null);
        }
        EntityType entityType;
        try {
            entityType = EntityType.valueOf(entity.asString());
        } catch (IllegalArgumentException e) {
            throw new SchemaViolationException("Unknown entity_type " + entity.asString());
        }
        if (entityType != expected) {
            throw new SchemaViolationException(
                    "Expected entity_type " + expected + " on this topic, got " + entityType);
        }
        PayloadType type = PayloadType.of(entityType, version.asInt())
                .orElseThrow(() -> new SchemaViolationException(
                        "Unsupported schema_version " + version.asInt() + " for " + entityType));
        List<String> errors = schemas.validate(tree);
        if (!errors.isEmpty()) {
            throw new SchemaViolationException(errors);
        }
        Envelope<? extends Payload> envelope;
        try {
            envelope = MessageJson.toEnvelope(tree);
        } catch (JacksonException e) {
            throw new SchemaViolationException(e.getOriginalMessage());
        }
        Set<ConstraintViolation<Envelope<? extends Payload>>> violations = validator.validate(envelope);
        if (!violations.isEmpty()) {
            throw new SchemaViolationException(violations.stream()
                    .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .toList());
        }
        if (type.javaType() != envelope.payload().getClass()) {
            throw new SchemaViolationException("Payload does not match " + type);
        }
        return new Read(tree, envelope, PayloadHasher.hash(tree));
    }

    /** Parses the bytes into a JSON object, or fails with {@code DESERIALIZE}. */
    public static JsonNode tree(byte[] value) {
        String text = Utf8.decode(value);
        JsonNode tree;
        try {
            tree = MessageJson.mapper().readTree(text);
        } catch (JacksonException e) {
            throw new DeserializationException("Malformed JSON: " + e.getOriginalMessage(), e);
        }
        if (tree == null || !tree.isObject()) {
            throw new DeserializationException("Message is not a JSON object", null);
        }
        return tree;
    }
}
