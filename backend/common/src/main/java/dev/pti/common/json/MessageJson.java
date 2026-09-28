package dev.pti.common.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.pti.common.message.EntityType;
import dev.pti.common.message.Envelope;
import dev.pti.common.message.Payload;
import dev.pti.common.message.PayloadType;
import dev.pti.common.time.Timestamps;
import java.time.Instant;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * The Jackson setup for Kafka messages (DOC-09): snake_case names come from the DTOs, timestamps are written in
 * the {@link Timestamps} form, absent optional fields are omitted, and reading is strict (unknown fields, scalar
 * coercion and numeric enums fail), matching {@code additionalProperties: false} in the schemas. REST JSON uses
 * Spring Boot's own mapper with camelCase (DR-39); the two are never mixed.
 */
public final class MessageJson {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .addModule(new SimpleModule("pti-messages").addSerializer(Instant.class, new InstantSerializer()))
            .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    private MessageJson() {}

    public static JsonMapper mapper() {
        return MAPPER;
    }

    /**
     * Binds a parsed message to the envelope type its {@code entity_type} and {@code schema_version} name.
     *
     * @throws IllegalArgumentException when the pair is not a supported {@link PayloadType}
     * @throws tools.jackson.core.JacksonException when the message does not bind
     */
    public static Envelope<? extends Payload> toEnvelope(JsonNode message) {
        PayloadType type = payloadType(message);
        JavaType javaType = MAPPER.getTypeFactory().constructParametricType(Envelope.class, type.javaType());
        return MAPPER.treeToValue(message, javaType);
    }

    /** The payload type named by the envelope fields, without binding the payload. */
    public static PayloadType payloadType(JsonNode message) {
        JsonNode entityType = message.path("entity_type");
        JsonNode version = message.path("schema_version");
        if (!entityType.isString() || !version.isInt()) {
            throw new IllegalArgumentException("entity_type and schema_version are required");
        }
        EntityType entity;
        try {
            entity = EntityType.valueOf(entityType.asString());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown entity_type " + entityType.asString(), e);
        }
        return PayloadType.of(entity, version.asInt())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported schema_version " + version.asInt() + " for " + entity));
    }

    private static final class InstantSerializer extends StdSerializer<Instant> {

        InstantSerializer() {
            super(Instant.class);
        }

        @Override
        public void serialize(Instant value, JsonGenerator gen, SerializationContext context) {
            gen.writeString(Timestamps.format(value));
        }
    }
}
