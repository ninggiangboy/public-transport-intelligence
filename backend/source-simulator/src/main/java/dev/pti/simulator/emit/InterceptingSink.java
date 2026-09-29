package dev.pti.simulator.emit;

import dev.pti.common.time.BusinessClock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Passes each message through the running scenarios' interceptors, in their fixed order (DOC-25 §7.1), then hands
 * what comes out to the real sink.
 */
public final class InterceptingSink implements MessageSink {

    private final MessageSink delegate;
    private final Supplier<List<MessageInterceptor>> interceptors;
    private final BusinessClock clock;

    public InterceptingSink(
            MessageSink delegate, Supplier<List<MessageInterceptor>> interceptors, BusinessClock clock) {
        this.delegate = delegate;
        this.interceptors = interceptors;
        this.clock = clock;
    }

    /** @return {@code false} when any resulting message was not handed to the producer */
    @Override
    public boolean send(OutboundMessage message) {
        List<MessageInterceptor> chain = interceptors.get();
        if (chain.isEmpty()) {
            return delegate.send(message);
        }
        long now = clock.millis();
        List<OutboundMessage> out = List.of(message);
        for (MessageInterceptor interceptor : chain) {
            List<OutboundMessage> next = new ArrayList<>();
            for (OutboundMessage m : out) {
                next.addAll(interceptor.intercept(m, now));
            }
            out = next;
        }
        boolean sent = true;
        for (OutboundMessage m : out) {
            sent &= delegate.send(m);
        }
        return sent;
    }
}
