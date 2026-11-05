package dev.pti.etl.stream.application.port;

import dev.pti.etl.stream.application.MicroBatchCommitted;

/**
 * Publishes the commit event after the chunk transaction has returned (DOC-19 §6.2, DR-35). Implemented by
 * {@code stream.adapter.out.event}; the baseline profile wires a no-op one (DOC-20 §9).
 */
public interface BatchCommittedPublisher {

    void publish(MicroBatchCommitted event);
}
