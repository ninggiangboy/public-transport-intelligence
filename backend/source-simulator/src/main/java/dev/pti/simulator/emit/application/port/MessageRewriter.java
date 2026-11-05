package dev.pti.simulator.emit.application.port;

import java.time.Instant;
import java.util.UUID;

/**
 * Rewrites the identity of an already-serialized message (DOC-25 §7.5), as a port so that {@code application} stays
 * free of Jackson. The implementation, {@code emit.adapter.out.json.JacksonMessageRewriter}, uses the shared message
 * JSON.
 */
public interface MessageRewriter {

    /** The same message as {@code value}, with a new {@code message_id} and {@code produced_at}. */
    String rewrite(String value, UUID messageId, Instant producedAt);
}
