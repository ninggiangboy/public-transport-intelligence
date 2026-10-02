package dev.pti.api.stream.adapter.in.sse;

import dev.pti.api.stream.application.EventHub;
import org.springframework.context.SmartLifecycle;

/**
 * Ends the event streams before the web server's graceful shutdown waits for requests (DOC-26 §7.1): new connections
 * get 503 and the open ones are completed, so the clients reconnect to another pod with {@code Last-Event-ID}. The
 * consumer container stops after this, in its own lower phase.
 */
public final class SseShutdown implements SmartLifecycle {

    private final EventHub hub;
    private volatile boolean running;

    public SseShutdown(EventHub hub) {
        this.hub = hub;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        hub.shutdown();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Above the web server's graceful shutdown phase ({@code Integer.MAX_VALUE - 1024}), so it stops first. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1;
    }
}
