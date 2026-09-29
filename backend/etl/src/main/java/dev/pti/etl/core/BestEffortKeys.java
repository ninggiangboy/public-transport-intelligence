package dev.pti.etl.core;

import dev.pti.common.json.MessageJson;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/** Extracts the business key of a message that failed, as far as it parses (DOC-22 §1.2). */
public final class BestEffortKeys {

    private BestEffortKeys() {}

    public static @Nullable String of(InboundMessage message, Function<JsonNode, @Nullable String> extractor) {
        byte[] value = message.value();
        if (value == null) {
            return null;
        }
        try {
            JsonNode tree = MessageJson.mapper().readTree(new String(value, StandardCharsets.UTF_8));
            return tree == null || !tree.isObject() ? null : extractor.apply(tree);
        } catch (JacksonException | IllegalArgumentException e) {
            return null;
        }
    }
}
