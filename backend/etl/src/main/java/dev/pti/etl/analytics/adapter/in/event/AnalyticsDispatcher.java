package dev.pti.etl.analytics.adapter.in.event;

import dev.pti.etl.analytics.application.BatchCommit;
import dev.pti.etl.analytics.application.DispatchBatchAnalytics;
import dev.pti.etl.analytics.application.DispatchSummary;
import dev.pti.etl.stream.MicroBatchCommitted;
import io.micrometer.tracing.Link;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;

/**
 * Receives {@link MicroBatchCommitted} after the commit and hands it to analytics on the {@code analyticsExecutor},
 * so the Kafka listener thread is never held (DOC-23 §4.1, DOC-20 §8). The trace of the run is a new root linked to
 * the {@code pti.etl.poll} span, not a child of it, because it runs later on another thread (DOC-23 §14.3).
 *
 * <p>DOC-23 §14.3 tags the span with the detector and the route; one dispatch covers every route of the micro-batch,
 * so the span here carries the trigger and the source, and {@code outcome} is {@code error} when any run failed. The
 * individual runs are told apart by the {@code batch_id} in the log (§14.2).
 */
public class AnalyticsDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsDispatcher.class);

    private final DispatchBatchAnalytics dispatch;
    private final Tracer tracer;

    public AnalyticsDispatcher(DispatchBatchAnalytics dispatch, Tracer tracer) {
        this.dispatch = dispatch;
        this.tracer = tracer;
    }

    @Async("analyticsExecutor")
    @EventListener
    public void onBatch(MicroBatchCommitted event) {
        BatchCommit batch = new BatchCommit(
                event.batchId(),
                event.source(),
                event.routeIds(),
                event.minEventTs(),
                event.minRecordTs(),
                event.committedAt());
        if (batch.triggeredDetector().isEmpty()) {
            return;
        }
        Span.Builder builder = tracer.spanBuilder()
                .setNoParent()
                .name("pti.analytics.run")
                .tag("trigger", "batch")
                .tag("source", batch.source().name());
        if (event.pollTrace() != null) {
            builder.addLink(new Link(event.pollTrace()));
        }
        Span span = builder.start();
        try (Tracer.SpanInScope _ = tracer.withSpan(span)) {
            DispatchSummary summary = dispatch.execute(batch);
            span.tag("outcome", summary.errors() > 0 ? "error" : "ok");
        } catch (RuntimeException e) {
            // The use case catches every failure of a run; this is for anything outside one.
            span.tag("outcome", "error");
            span.error(e);
            log.error("analytics dispatch failed for micro-batch {}", batch.batchId(), e);
        } finally {
            span.end();
        }
    }
}
