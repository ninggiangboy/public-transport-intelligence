package dev.pti.etl.core.adapter.out.json;

import dev.pti.common.json.MessageJson;
import dev.pti.etl.core.application.port.BestEffortKeys;
import dev.pti.etl.core.domain.InboundMessage;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/** Best-effort key extraction on the Jackson tree of the raw bytes (DOC-22 §1.2). */
public final class JacksonBestEffortKeys implements BestEffortKeys {

    @Override
    public @Nullable String of(InboundMessage message, Function<Fields, @Nullable String> extractor) {
        byte[] value = message.value();
        if (value == null) {
            return null;
        }
        try {
            JsonNode tree = MessageJson.mapper().readTree(new String(value, StandardCharsets.UTF_8));
            if (tree == null || !tree.isObject()) {
                return null;
            }
            return extractor.apply(new JsonFields(tree));
        } catch (JacksonException | IllegalArgumentException e) {
            return null;
        }
    }

    private record JsonFields(JsonNode node) implements Fields {

        @Override
        public Fields path(String name) {
            return new JsonFields(node.path(name));
        }

        @Override
        public @Nullable String asString() {
            return node.asString(null);
        }
    }
}
