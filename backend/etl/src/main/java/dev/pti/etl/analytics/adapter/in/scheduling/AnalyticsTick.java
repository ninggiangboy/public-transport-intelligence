package dev.pti.etl.analytics.adapter.in.scheduling;

import dev.pti.etl.analytics.application.DispatchSummary;
import dev.pti.etl.analytics.application.RunAnalyticsTick;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The tick of DOC-23 §4.2. The scheduler thread only submits the work to the {@code analyticsExecutor}, so it is
 * never held by a database call.
 */
public class AnalyticsTick {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsTick.class);

    private final RunAnalyticsTick tick;
    private final Executor executor;
    private final Tracer tracer;

    public AnalyticsTick(RunAnalyticsTick tick, Executor executor, Tracer tracer) {
        this.tick = tick;
        this.executor = executor;
        this.tracer = tracer;
    }

    @Scheduled(
            initialDelayString = "${pti.analytics.dispatcher.tick-interval}",
            fixedDelayString = "${pti.analytics.dispatcher.tick-interval}",
            scheduler = "ptiTaskScheduler")
    public void tick() {
        try {
            executor.execute(this::run);
        } catch (RejectedExecutionException e) {
            log.debug("The analytics executor is shut down, tick skipped");
        }
    }

    private void run() {
        Span span = tracer.spanBuilder()
                .setNoParent()
                .name("pti.analytics.run")
                .tag("trigger", "tick")
                .start();
        try (Tracer.SpanInScope _ = tracer.withSpan(span)) {
            DispatchSummary summary = tick.execute();
            span.tag("outcome", summary.errors() > 0 ? "error" : "ok");
        } catch (RuntimeException e) {
            span.tag("outcome", "error");
            span.error(e);
            log.error("analytics tick failed", e);
        } finally {
            span.end();
        }
    }
}
