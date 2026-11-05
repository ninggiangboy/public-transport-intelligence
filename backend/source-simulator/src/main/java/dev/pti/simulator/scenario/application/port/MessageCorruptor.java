package dev.pti.simulator.scenario.application.port;

import dev.pti.simulator.emit.domain.OutboundMessage;
import dev.pti.simulator.scenario.domain.InvalidKind;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Corrupts one message the way the {@code bad-data} scenario does (DOC-25 §7.4). The implementation,
 * {@code scenario.adapter.out.json.JacksonMessageCorruptor}, works on the JSON tree.
 */
public interface MessageCorruptor {

    /**
     * @param choice a deterministic random value that picks the variant (cut position, field, sign)
     * @param businessNow business time now, epoch milliseconds
     * @return {@code null} when the kind cannot apply to this message: a trip update whose stops are all skipped has
     *     no delay to change
     */
    @Nullable
    OutboundMessage corrupt(OutboundMessage message, InvalidKind kind, long choice, long businessNow, UUID runId);
}
