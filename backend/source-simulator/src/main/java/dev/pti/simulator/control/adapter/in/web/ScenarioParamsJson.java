package dev.pti.simulator.control.adapter.in.web;

import dev.pti.simulator.scenario.domain.InvalidKind;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.module.SimpleModule;

/**
 * The Jackson setup of the scenario API (DOC-25 §8): an {@link InvalidKind} is its lower-case wire value
 * ({@code malformed_json}), both in the request body and in the catalog. The scenario records stay plain Java
 * (DR-115); only the adapter knows the wire names.
 */
public final class ScenarioParamsJson {

    private ScenarioParamsJson() {}

    /** The module that writes and reads the wire value of {@link InvalidKind}. */
    public static SimpleModule module() {
        return new SimpleModule("pti-scenario-params")
                .addSerializer(InvalidKind.class, new InvalidKindSerializer())
                .addDeserializer(InvalidKind.class, new InvalidKindDeserializer());
    }

    private static final class InvalidKindSerializer extends ValueSerializer<InvalidKind> {

        @Override
        public void serialize(InvalidKind value, JsonGenerator gen, SerializationContext context)
                throws JacksonException {
            gen.writeString(value.value());
        }
    }

    private static final class InvalidKindDeserializer extends ValueDeserializer<InvalidKind> {

        @Override
        public InvalidKind deserialize(JsonParser p, DeserializationContext context) throws JacksonException {
            String value = p.getValueAsString();
            try {
                return InvalidKind.of(value);
            } catch (IllegalArgumentException e) {
                throw InvalidFormatException.from(p, "Unknown invalid kind " + value, value, InvalidKind.class);
            }
        }
    }
}
