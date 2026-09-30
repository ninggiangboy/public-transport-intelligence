package dev.pti.analytics.core.application;

import dev.pti.analytics.core.application.port.AnalyticsMetrics;
import dev.pti.analytics.core.domain.Detector;
import dev.pti.analytics.core.domain.Outcome;
import dev.pti.analytics.core.domain.RunResult;
import dev.pti.analytics.core.domain.Trigger;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Counts, times and logs one unit of analytics work that is not started by {@code SafeAdvance}: a route of the ETA
 * aggregation, a day of the OTP scorecard (DOC-23 §14). A live detector's caller does this for the detector; a job's
 * tasklet only calls a use case, so the use case reports for itself.
 *
 * <p>A unit that throws is counted as {@code error} and logged; the exception goes on to the caller, because a job
 * step must fail and restart from its context (DOC-23 §15).
 */
public final class RunReporter {

    private static final Logger log = LoggerFactory.getLogger(RunReporter.class);

    private final AnalyticsMetrics metrics;

    public RunReporter(AnalyticsMetrics metrics) {
        this.metrics = metrics;
    }

    /** Runs the work and reports its {@link RunResult}; {@code runDuration} only for a unit that had work. */
    public RunResult report(Detector detector, String scope, Trigger trigger, UUID batchId, Supplier<RunResult> work) {
        long start = System.nanoTime();
        RunResult result;
        try {
            result = work.get();
        } catch (RuntimeException e) {
            metrics.run(detector, trigger, Outcome.ERROR);
            log.atError()
                    .addKeyValue("detector", detector.tag())
                    .addKeyValue("scope", scope)
                    .addKeyValue("trigger", trigger.tag())
                    .addKeyValue("batchId", batchId)
                    .setCause(e)
                    .log("analytics run failed");
            throw e;
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        metrics.run(detector, trigger, result.outcome());
        if (result.outcome() == Outcome.OK) {
            metrics.runDuration(detector, elapsed);
        }
        var event = result.changedAnything() ? log.atInfo() : log.atDebug();
        event.addKeyValue("detector", result.detector().tag())
                .addKeyValue("scope", result.scope())
                .addKeyValue("trigger", result.trigger().tag())
                .addKeyValue("outcome", result.outcome().tag())
                .addKeyValue("batchId", result.batchId())
                .addKeyValue("gridPoints", result.gridPoints())
                .addKeyValue("opened", result.opened())
                .addKeyValue("updated", result.updated())
                .addKeyValue("closed", result.closed())
                .addKeyValue("deleted", result.deleted())
                .addKeyValue("durationMs", elapsed.toMillis())
                .log("analytics run finished");
        return result;
    }
}
