package dev.pti.common.message;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.pti.common.json.MessageJson;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * The JSON Schemas (draft 2020-12) of the envelope and of every payload version, shared by the simulator, the ETL
 * and their tests (DOC-09 §10, DR-44). Thread-safe; load once.
 */
public final class MessageSchemas {

    public static final String ENVELOPE_RESOURCE = "schemas/envelope.schema.json";

    private final Schema envelope;
    private final Map<PayloadType, Schema> payloads = new EnumMap<>(PayloadType.class);

    private MessageSchemas() {
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        this.envelope = load(registry, ENVELOPE_RESOURCE);
        for (PayloadType type : PayloadType.values()) {
            payloads.put(type, load(registry, type.schemaResource()));
        }
    }

    public static MessageSchemas load() {
        return new MessageSchemas();
    }

    /**
     * Validates a whole message: the envelope first, then the payload against the schema its {@code entity_type}
     * and {@code schema_version} select. Returns an empty list when valid.
     */
    public List<String> validate(JsonNode message) {
        List<String> errors = new ArrayList<>(messages(envelope.validate(message)));
        if (!errors.isEmpty()) {
            return errors;
        }
        PayloadType type;
        try {
            type = MessageJson.payloadType(message);
        } catch (IllegalArgumentException e) {
            return List.of(e.getMessage());
        }
        return validatePayload(type, message.get("payload"));
    }

    public List<String> validatePayload(PayloadType type, JsonNode payload) {
        return messages(payloads.get(type).validate(payload));
    }

    private static List<String> messages(List<Error> errors) {
        return errors.stream().map(Error::toString).toList();
    }

    private static Schema load(SchemaRegistry registry, String resource) {
        try (InputStream in = MessageSchemas.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing schema resource " + resource);
            }
            return registry.getSchema(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
