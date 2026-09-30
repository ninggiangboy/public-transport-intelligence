package dev.pti.analytics.core.application;

import dev.pti.analytics.core.domain.Detector;
import dev.pti.analytics.core.domain.RunContext;
import dev.pti.analytics.core.domain.RunResult;
import dev.pti.analytics.core.domain.Trigger;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A detector that advances per route along an event-time grid: bunching and disruption (DOC-23 §3). The dispatcher
 * calls it after a micro-batch and from the tick, and publishes the events of the result after the commit.
 */
public interface RouteDetector {

    Detector detector();

    /** False when the route's {@code route_type} is not in the detector's route types. */
    boolean enabledFor(String routeId);

    /**
     * Advances one route to its watermark in one transaction ({@code REQUIRES_NEW}, DOC-23 §12.1). Returns
     * {@code NOOP} without opening a write transaction when the grid is already evaluated up to the watermark.
     *
     * @param context what triggered the run and the {@code batch_id} to write; see {@link RunContext}
     */
    RunResult advance(String routeId, Trigger trigger, RunContext context);

    /** Routes that still hold state (open episodes, pending counters) and need the idle tick. */
    List<String> routesNeedingTick();

    /** Current cursor of a route, for the late-data check (DOC-23 §2.2). */
    Optional<Instant> cursor(String routeId);
}
