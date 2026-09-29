package dev.pti.simulator.emit;

import java.util.List;

/**
 * Changes, replaces or holds back outgoing messages while a scenario runs (DOC-25 §7.1): {@code duplicates} and
 * {@code bad-data}. Called on the thread that sends, before the message reaches Kafka.
 */
public interface MessageInterceptor {

    /**
     * @param businessNow business time now, in epoch milliseconds
     * @return the messages to send instead of {@code message}, in order; may be empty
     */
    List<OutboundMessage> intercept(OutboundMessage message, long businessNow);
}
