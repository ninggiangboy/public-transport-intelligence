package dev.pti.etl.analytics.application;

import dev.pti.analytics.core.application.RouteDetector;
import dev.pti.analytics.core.application.port.AnalyticsMetrics;
import dev.pti.analytics.core.domain.Detector;
import dev.pti.analytics.core.domain.Outcome;
import dev.pti.analytics.core.domain.Trigger;
import dev.pti.analytics.event.application.port.AnalyticsEventSink;
import dev.pti.common.time.BusinessClock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What analytics does after a micro-batch has committed (DOC-23 §4.1): for each route the batch wrote, advance the
 * detector that the batch's source triggers, then publish the events of the run. A run that fails is logged and
 * counted and the next route goes on; it never reaches the chunk or its offset (DR-35).
 */
public class DispatchBatchAnalytics {

    private static final Logger log = LoggerFactory.getLogger(DispatchBatchAnalytics.class);

    private final List<RouteDetector> detectors;
    private final AnalyticsMetrics metrics;
    private final SafeAdvance safeAdvance;

    public DispatchBatchAnalytics(
            List<RouteDetector> detectors, AnalyticsMetrics metrics, AnalyticsEventSink sink, BusinessClock clock) {
        this.detectors = List.copyOf(detectors);
        this.metrics = metrics;
        this.safeAdvance = new SafeAdvance(metrics, sink, clock);
    }

    public DispatchSummary execute(BatchCommit batch) {
        Optional<Detector> triggered = batch.triggeredDetector();
        if (triggered.isEmpty()) {
            return DispatchSummary.NONE;
        }
        metrics.dispatchDelay(safeAdvance.sinceCommit(batch.committedAt()));
        int runs = 0;
        int errors = 0;
        for (RouteDetector detector : detectors) {
            if (detector.detector() != triggered.get()) {
                continue;
            }
            boolean lateCounted = false;
            for (String routeId : batch.routeIds().stream().sorted().toList()) {
                if (!detector.enabledFor(routeId)) {
                    continue;
                }
                if (!lateCounted && isLate(detector, routeId, batch)) {
                    // Once per micro-batch and detector, however many of its routes are late (DOC-23 §2.2).
                    metrics.lateBatch(detector.detector());
                    lateCounted = true;
                }
                runs++;
                if (safeAdvance.run(detector, routeId, Trigger.BATCH, batch) == Outcome.ERROR) {
                    errors++;
                }
            }
        }
        return new DispatchSummary(runs, errors);
    }

    /** The batch holds data at or before the cursor, which the live path does not evaluate again. */
    private boolean isLate(RouteDetector detector, String routeId, BatchCommit batch) {
        Instant minEventTs = batch.minEventTs();
        if (minEventTs == null) {
            return false;
        }
        try {
            return detector.cursor(routeId)
                    .filter(cursor -> !minEventTs.isAfter(cursor))
                    .isPresent();
        } catch (RuntimeException e) {
            // The run that follows reads the same database; a failure shows up there as outcome="error".
            log.debug(
                    "Cannot read the cursor of {} on route {} for the late-data check",
                    detector.detector(),
                    routeId,
                    e);
            return false;
        }
    }
}
