package dev.pti.simulator.emit.adapter.out.json;

import dev.pti.common.json.MessageJson;
import dev.pti.common.time.Timestamps;
import dev.pti.simulator.emit.application.port.MessageRewriter;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.node.ObjectNode;

/** {@link MessageRewriter} on the shared message JSON (DOC-09): it replaces {@code message_id} and {@code produced_at}. */
public final class JacksonMessageRewriter implements MessageRewriter {

    @Override
    public String rewrite(String value, UUID messageId, Instant producedAt) {
        ObjectNode tree = (ObjectNode) MessageJson.mapper().readTree(value);
        tree.put("message_id", messageId.toString());
        tree.put("produced_at", Timestamps.format(producedAt));
        return MessageJson.mapper().writeValueAsString(tree);
    }
}
