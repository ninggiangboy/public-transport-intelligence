package dev.pti.simulator.emit.application.port;

import dev.pti.simulator.emit.domain.OutboundMessage;

/** Where the emitter sends messages. Implementations are thread-safe. */
public interface MessageSink {

    /**
     * Sends asynchronously; the ledger entry is written only from the success callback (DOC-25 §6.4).
     *
     * @return {@code false} when the message was not handed to the producer and the emission is skipped
     */
    boolean send(OutboundMessage message);
}
