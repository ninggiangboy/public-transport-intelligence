package dev.pti.etl.analytics;

import dev.pti.analytics.core.application.RouteDetector;
import dev.pti.analytics.core.domain.Detector;
import dev.pti.analytics.core.domain.Outcome;
import dev.pti.analytics.core.domain.RunContext;
import dev.pti.analytics.core.domain.RunResult;
import dev.pti.analytics.core.domain.Trigger;
import dev.pti.analytics.event.domain.InsightEvent;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** A bunching detector for the wiring tests: records its calls and the thread they ran on, can block and can fail. */
public final class RecordingDetector implements RouteDetector {

    /** One call of {@code advance}. */
    public record Advance(String routeId, Trigger trigger, RunContext context, String thread) {}

    private final List<Advance> advances = new CopyOnWriteArrayList<>();
    private final Set<String> failingRoutes = new HashSet<>();
    private final Map<String, List<InsightEvent>> events = new HashMap<>();
    private volatile List<String> tickRoutes = List.of();
    private volatile CountDownLatch gate = new CountDownLatch(0);

    public void reset() {
        advances.clear();
        failingRoutes.clear();
        events.clear();
        tickRoutes = List.of();
        gate = new CountDownLatch(0);
    }

    /** Makes every {@code advance} wait until the returned latch is counted down. */
    public CountDownLatch blockAdvances() {
        gate = new CountDownLatch(1);
        return gate;
    }

    public void failOn(String routeId) {
        failingRoutes.add(routeId);
    }

    public void emit(String routeId, InsightEvent event) {
        events.put(routeId, List.of(event));
    }

    public void needTick(String... routeIds) {
        tickRoutes = List.of(routeIds);
    }

    public List<Advance> advances() {
        return List.copyOf(advances);
    }

    @Override
    public Detector detector() {
        return Detector.BUNCHING;
    }

    @Override
    public boolean enabledFor(String routeId) {
        return true;
    }

    @Override
    public RunResult advance(String routeId, Trigger trigger, RunContext context) {
        advances.add(
                new Advance(routeId, trigger, context, Thread.currentThread().getName()));
        try {
            gate.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (failingRoutes.contains(routeId)) {
            throw new IllegalStateException("The database is gone");
        }
        List<InsightEvent> emitted = events.getOrDefault(routeId, List.of());
        return new RunResult(
                Detector.BUNCHING,
                routeId,
                trigger,
                Outcome.OK,
                context.batchId(),
                1,
                emitted.size(),
                0,
                0,
                0,
                emitted);
    }

    @Override
    public List<String> routesNeedingTick() {
        return tickRoutes;
    }

    @Override
    public Optional<Instant> cursor(String routeId) {
        return Optional.empty();
    }
}
