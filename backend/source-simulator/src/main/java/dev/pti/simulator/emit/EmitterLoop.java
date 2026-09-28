package dev.pti.simulator.emit;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Runs {@link Emitter#tick()} every {@code pti.sim.tick} on the {@code sim-emitter} thread (DOC-25 §10). Starts after
 * the Kafka sink and stops before it, so the sink can flush what the loop sent.
 */
public final class EmitterLoop implements SmartLifecycle {

    public static final int PHASE = 1_000;

    private static final Logger log = LoggerFactory.getLogger(EmitterLoop.class);

    private static final long WARN_EVERY_NANOS = TimeUnit.SECONDS.toNanos(10);

    private final Emitter emitter;
    private final Duration tick;
    private @Nullable ScheduledExecutorService executor;
    private long lastError = Long.MIN_VALUE;

    public EmitterLoop(Emitter emitter, Duration tick) {
        this.emitter = emitter;
        this.tick = tick;
    }

    @Override
    public synchronized void start() {
        // The first tick rebuilds the vehicles already on the road before the loop starts (DOC-25 §10).
        safeTick();
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("sim-emitter").factory());
        ex.scheduleWithFixedDelay(this::safeTick, tick.toMillis(), tick.toMillis(), TimeUnit.MILLISECONDS);
        executor = ex;
    }

    private void safeTick() {
        try {
            emitter.tick();
        } catch (RuntimeException e) {
            // A failing tick must not kill the loop: the next one starts from where this one stopped.
            long now = System.nanoTime();
            if (lastError == Long.MIN_VALUE || now - lastError >= WARN_EVERY_NANOS) {
                lastError = now;
                log.error("Emitter tick failed", e);
            }
        }
    }

    @Override
    public synchronized void stop() {
        ScheduledExecutorService ex = executor;
        if (ex == null) {
            return;
        }
        ex.shutdown();
        try {
            if (!ex.awaitTermination(10, TimeUnit.SECONDS)) {
                ex.shutdownNow();
            }
        } catch (InterruptedException e) {
            ex.shutdownNow();
            Thread.currentThread().interrupt();
        }
        executor = null;
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
