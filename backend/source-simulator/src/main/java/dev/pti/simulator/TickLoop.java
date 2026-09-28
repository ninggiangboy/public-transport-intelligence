package dev.pti.simulator;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Runs a task every {@code pti.sim.tick} on its own platform thread (DOC-25 §10): {@code sim-emitter},
 * {@code sim-ticketing}. The first run happens in {@link #start()}, so the vehicles already on the road are rebuilt
 * before the app reports ready. A failing run is logged and the next one carries on.
 */
public final class TickLoop implements SmartLifecycle {

    /** After the Kafka flush and the ledger writer, so those stop after the loops. */
    public static final int PHASE = 1_000;

    private static final Logger log = LoggerFactory.getLogger(TickLoop.class);

    private static final long WARN_EVERY_NANOS = TimeUnit.SECONDS.toNanos(10);

    private final String name;
    private final Runnable task;
    private final Duration tick;
    private @Nullable ScheduledExecutorService executor;
    private long lastError = Long.MIN_VALUE;

    public TickLoop(String name, Runnable task, Duration tick) {
        this.name = name;
        this.task = task;
        this.tick = tick;
    }

    @Override
    public synchronized void start() {
        safeTick();
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name(name).factory());
        ex.scheduleWithFixedDelay(this::safeTick, tick.toMillis(), tick.toMillis(), TimeUnit.MILLISECONDS);
        executor = ex;
    }

    private void safeTick() {
        try {
            task.run();
        } catch (RuntimeException e) {
            // A failing tick must not kill the loop: the next one starts from where this one stopped.
            long now = System.nanoTime();
            if (lastError == Long.MIN_VALUE || now - lastError >= WARN_EVERY_NANOS) {
                lastError = now;
                log.error("{} tick failed", name, e);
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
