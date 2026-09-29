package dev.pti.etl.core.cdc;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.pti.common.error.SchemaViolationException;
import dev.pti.common.json.MessageJson;
import dev.pti.etl.core.gtfsrt.EnvelopeReader;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Comparator;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Reads an unwrapped Debezium value into its DTO: {@code DESERIALIZE} for bad bytes, DQ-01 otherwise (DOC-20 §4.4). */
public final class CdcReader {

    /** A CDC value that passed DQ-01, with the parsed tree for hashing. */
    public record Read<T>(ObjectNode tree, T value) {}

    private final Validator validator;

    public CdcReader(Validator validator) {
        this.validator = validator;
    }

    public <T> Read<T> read(byte[] bytes, Class<T> type) {
        JsonNode tree = EnvelopeReader.tree(bytes);
        T value;
        try {
            value = MessageJson.mapper().treeToValue(tree, type);
        } catch (JacksonException e) {
            throw new SchemaViolationException(e.getOriginalMessage());
        }
        Set<ConstraintViolation<T>> violations = validator.validate(value);
        if (!violations.isEmpty()) {
            throw new SchemaViolationException(violations.stream()
                    .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                    .map(v -> jsonName(type, v.getPropertyPath().toString()) + ": " + v.getMessage())
                    .toList());
        }
        return new Read<>((ObjectNode) tree, value);
    }

    /** The field as it appears in the message, e.g. {@code sale_point_id} for {@code salePointId}. */
    static String jsonName(Class<?> type, String property) {
        try {
            JsonProperty json = type.getDeclaredField(property).getAnnotation(JsonProperty.class);
            return json == null || json.value().isEmpty() ? property : json.value();
        } catch (NoSuchFieldException e) {
            return property;
        }
    }
}
