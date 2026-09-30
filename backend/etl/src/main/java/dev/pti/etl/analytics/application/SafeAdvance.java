package dev.pti.etl.analytics.application;

import dev.pti.analytics.core.application.RouteDetector;
import dev.pti.analytics.core.application.port.AnalyticsMetrics;
import dev.pti.analytics.core.domain.Outcome;
import dev.pti.analytics.core.domain.RunContext;
import dev.pti.analytics.core.domain.RunResult;
import dev.pti.analytics.core.domain.Trigger;
import dev.pti.analytics.event.application.port.AnalyticsEventSink;
import dev.pti.common.time.BusinessClock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * One {@code advance} of one detector on one route, run so that nothing it does can reach the caller: an analytics
 * failure never touches a chunk or an offset (DOC-23 §4.1, DR-35). The run is logged, counted, and its events are
 * published after its transaction has committed.
 */
final class SafeAdvance {

    static final String MDC_BATCH_ID = "batch_id";
    static final String MDC_SOURCE_BATCH_ID = "source_batch_id";

    private static final Logger log = LoggerFactory.getLogger(SafeAdvance.class);

    private final AnalyticsMetrics metrics;
    private final AnalyticsEventSink sink;
    private final BusinessClock clock;

    SafeAdvance(AnalyticsMetrics metrics, AnalyticsEventSink sink, BusinessClock clock) {
        this.metrics = metrics;
        this.sink = sink;
        this.clock = clock;
    }

    /**
     * @param sourceBatch the context of the micro-batch that triggered the run, without its own {@code batch_id};
     *     {@code null} for a tick
     * @return the outcome of the run, {@code ERROR} when it threw
     */
    Outcome run(RouteDetector detector, String routeId, Trigger trigger, @Nullable BatchCommit sourceBatch) {
        UUID batchId = RunResult.newBatchId();
        RunContext context = sourceBatch == null
                ? RunContext.untriggered(batchId)
                : RunContext.afterBatch(
                        batchId,
                        sourceBatch.batchId(),
                        sourceBatch.minEventTs(),
                        sourceBatch.minRecordTs(),
                        sourceBatch.committedAt());
        MDC.put(MDC_BATCH_ID, batchId.toString());
        if (sourceBatch != null) {
            MDC.put(MDC_SOURCE_BATCH_ID, sourceBatch.batchId().toString());
        }
        long start = System.nanoTime();
        try {
            RunResult result = detector.advance(routeId, trigger, context);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
            metrics.run(detector.detector(), trigger, result.outcome());
            if (result.outcome() == Outcome.OK) {
                metrics.runDuration(detector.detector(), elapsed);
            }
            logFinished(result, elapsed);
            publish(result);
            return result.outcome();
        } catch (RuntimeException e) {
            metrics.run(detector.detector(), trigger, Outcome.ERROR);
            log.atError()
                    .addKeyValue("detector", detector.detector().tag())
                    .addKeyValue("scope", routeId)
                    .addKeyValue("trigger", trigger.tag())
                    .addKeyValue("sourceBatchId", sourceBatch == null ? null : sourceBatch.batchId())
                    .setCause(e)
                    .log("analytics run failed");
            return Outcome.ERROR;
        } finally {
            MDC.remove(MDC_BATCH_ID);
            MDC.remove(MDC_SOURCE_BATCH_ID);
        }
    }

    private void logFinished(RunResult result, Duration elapsed) {
        var event = result.changedAnything() ? log.atInfo() : log.atDebug();
        event.addKeyValue("detector", result.detector().tag())
                .addKeyValue("scope", result.scope())
                .addKeyValue("trigger", result.trigger().tag())
                .addKeyValue("outcome", result.outcome().tag())
                .addKeyValue("batchId", result.batchId())
                .addKeyValue("gridPoints", result.gridPoints())
                .addKeyValue("opened", result.opened())
                .addKeyValue("closed", result.closed())
                .addKeyValue("deleted", result.deleted())
                .addKeyValue("durationMs", elapsed.toMillis())
                .log("analytics run finished");
    }

    /** After the commit. The sink must not throw; if it does anyway the run still counts as done. */
    private void publish(RunResult result) {
        if (result.events().isEmpty()) {
            return;
        }
        try {
            sink.publish(result.events());
        } catch (RuntimeException e) {
            log.warn(
                    "The analytics event sink failed for {} events",
                    result.events().size(),
                    e);
        }
    }

    /** Real time now, for the dispatch delay. */
    Duration sinceCommit(Instant committedAt) {
        Duration delay = Duration.between(committedAt, clock.realNow());
        return delay.isNegative() ? Duration.ZERO : delay;
    }
}
