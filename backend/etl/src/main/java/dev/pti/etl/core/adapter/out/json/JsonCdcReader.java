package dev.pti.etl.core.adapter.out.json;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.pti.common.error.SchemaViolationException;
import dev.pti.common.json.MessageJson;
import dev.pti.common.json.PayloadHasher;
import dev.pti.etl.core.application.port.CdcReader;
import dev.pti.etl.core.domain.SalePointCdc;
import dev.pti.etl.core.domain.TicketTransactionCdc;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Comparator;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Reads an unwrapped Debezium value into its domain record: {@code DESERIALIZE} for bad bytes, DQ-01 otherwise. */
public final class JsonCdcReader implements CdcReader {

    private final Validator validator;

    public JsonCdcReader(Validator validator) {
        this.validator = validator;
    }

    @Override
    public Read<SalePointCdc> salePoint(byte[] value) {
        ObjectNode tree = tree(value);
        SalePointCdcMessage message = bind(tree, SalePointCdcMessage.class);
        validate(message);
        return new Read<>(message.toDomain(), PayloadHasher.hashCdc(tree));
    }

    @Override
    public Read<TicketTransactionCdc> ticketSale(byte[] value) {
        ObjectNode tree = tree(value);
        TicketTransactionCdcMessage message = bind(tree, TicketTransactionCdcMessage.class);
        validate(message);
        return new Read<>(message.toDomain(), PayloadHasher.hashCdc(tree));
    }

    private static <T> T bind(JsonNode tree, Class<T> type) {
        try {
            return MessageJson.mapper().treeToValue(tree, type);
        } catch (JacksonException e) {
            throw new SchemaViolationException(e.getOriginalMessage());
        }
    }

    private <T> void validate(T value) {
        Set<ConstraintViolation<T>> violations = validator.validate(value);
        if (!violations.isEmpty()) {
            throw new SchemaViolationException(violations.stream()
                    .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                    .map(v -> jsonName(value.getClass(), v.getPropertyPath().toString()) + ": " + v.getMessage())
                    .toList());
        }
    }

    private static ObjectNode tree(byte[] value) {
        return (ObjectNode) JsonEnvelopeReader.tree(value);
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
