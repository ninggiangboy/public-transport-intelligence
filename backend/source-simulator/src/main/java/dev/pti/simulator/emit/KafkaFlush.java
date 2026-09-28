package dev.pti.simulator.emit;

import org.springframework.context.SmartLifecycle;

/**
 * Flushes the producer on shutdown after the emitter loop has stopped (DOC-25 §10), so every sent record gets its
 * callback, and its ledger row, before the ledger drains.
 */
public final class KafkaFlush implements SmartLifecycle {

    public static final int PHASE = 500;

    private final KafkaMessageSink sink;
    private volatile boolean running;

    public KafkaFlush(KafkaMessageSink sink) {
        this.sink = sink;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        sink.flush();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
