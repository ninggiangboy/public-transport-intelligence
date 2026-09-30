package dev.pti.etl.analytics.application;

import dev.pti.analytics.core.application.RouteDetector;
import dev.pti.analytics.core.domain.Detector;
import dev.pti.analytics.core.domain.Outcome;
import dev.pti.analytics.core.domain.RunContext;
import dev.pti.analytics.core.domain.RunResult;
import dev.pti.analytics.core.domain.Trigger;
import dev.pti.analytics.event.domain.InsightEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.slf4j.MDC;

/** A {@link RouteDetector} that records its calls and answers as the test says. */
public final class FakeRouteDetector implements RouteDetector {

    /** One call of {@code advance}. */
    public record Advance(String routeId, Trigger trigger, RunContext context, String mdcBatchId, String mdcSource) {}

    private final Detector detector;
    private final Set<String> disabledRoutes = new HashSet<>();
    private final Map<String, Instant> cursors = new HashMap<>();
    private final Set<String> failingRoutes = new HashSet<>();
    private final Map<String, List<InsightEvent>> events = new HashMap<>();
    private final List<String> tickRoutes = new ArrayList<>();
    private final List<Advance> advances = new CopyOnWriteArrayList<>();
    private Function<String, Outcome> outcomes = route -> Outcome.OK;
    private boolean tickListFails;
    private boolean cursorFails;

    public FakeRouteDetector(Detector detector) {
        this.detector = detector;
    }

    public FakeRouteDetector disabledFor(String routeId) {
        disabledRoutes.add(routeId);
        return this;
    }

    public FakeRouteDetector cursorAt(String routeId, Instant cursor) {
        cursors.put(routeId, cursor);
        return this;
    }

    public FakeRouteDetector failingOn(String routeId) {
        failingRoutes.add(routeId);
        return this;
    }

    public FakeRouteDetector emitting(String routeId, InsightEvent... emitted) {
        events.put(routeId, List.of(emitted));
        return this;
    }

    public FakeRouteDetector needingTick(String... routeIds) {
        tickRoutes.addAll(List.of(routeIds));
        return this;
    }

    public FakeRouteDetector outcome(Function<String, Outcome> outcomes) {
        this.outcomes = outcomes;
        return this;
    }

    public FakeRouteDetector whoseTickListFails() {
        tickListFails = true;
        return this;
    }

    public FakeRouteDetector whoseCursorFails() {
        cursorFails = true;
        return this;
    }

    public List<Advance> advances() {
        return advances;
    }

    public List<String> advancedRoutes() {
        return advances.stream().map(Advance::routeId).toList();
    }

    @Override
    public Detector detector() {
        return detector;
    }

    @Override
    public boolean enabledFor(String routeId) {
        return !disabledRoutes.contains(routeId);
    }

    @Override
    public RunResult advance(String routeId, Trigger trigger, RunContext context) {
        advances.add(new Advance(routeId, trigger, context, MDC.get("batch_id"), MDC.get("source_batch_id")));
        if (failingRoutes.contains(routeId)) {
            throw new IllegalStateException("The database is gone");
        }
        Outcome outcome = outcomes.apply(routeId);
        List<InsightEvent> emitted = outcome == Outcome.OK ? events.getOrDefault(routeId, List.of()) : List.of();
        int opened = emitted.isEmpty() ? 0 : 1;
        return new RunResult(detector, routeId, trigger, outcome, context.batchId(), 4, opened, 0, 0, 0, emitted);
    }

    @Override
    public List<String> routesNeedingTick() {
        if (tickListFails) {
            throw new IllegalStateException("The database is gone");
        }
        return tickRoutes;
    }

    @Override
    public Optional<Instant> cursor(String routeId) {
        if (cursorFails) {
            throw new IllegalStateException("The database is gone");
        }
        return Optional.ofNullable(cursors.get(routeId));
    }
}
