package dev.pti.etl.stream;

import dev.pti.etl.core.InboundMessage;
import io.micrometer.tracing.Link;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The spans of one poll (DOC-28 §5.2). A batch listener has no observation per record, so {@code pti.etl.poll} is a
 * root span linked to the producer spans named in the records' {@code traceparent} headers (at most
 * {@code maxLinks}); {@code pti.etl.process}, {@code pti.etl.write}, {@code pti.etl.dlq.write} and
 * {@code pti.etl.commit} are its children. With tracing off, the tracer is a no-op and so is everything here.
 */
public final class PollTracing {

    /** W3C trace context: {@code version-traceid-parentid-flags}. */
    private static final Pattern TRACEPARENT =
            Pattern.compile("^[0-9a-f]{2}-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");

    public static final String TRACEPARENT_HEADER = "traceparent";

    public static final PollTracing NOOP = new PollTracing(Tracer.NOOP, 0);

    private final Tracer tracer;
    private final int maxLinks;

    public PollTracing(Tracer tracer, int maxLinks) {
        this.tracer = tracer;
        this.maxLinks = maxLinks;
    }

    /** Starts {@code pti.etl.poll} and puts it in scope; closing the result ends it. */
    public Poll poll(StreamChunkRequest request) {
        Span.Builder builder = tracer.spanBuilder()
                .setNoParent()
                .name("pti.etl.poll")
                .tag("source", request.source().name())
                .tag("listener", request.listenerId());
        Set<String> linked = new HashSet<>();
        for (InboundMessage message : request.messages()) {
            if (linked.size() >= maxLinks) {
                break;
            }
            TraceContext producer = producerContext(message.headers().get(TRACEPARENT_HEADER));
            if (producer != null && linked.add(producer.spanId())) {
                builder.addLink(new Link(producer));
            }
        }
        Span span = builder.start();
        span.event("records=" + request.messages().size());
        return new Poll(span, tracer.withSpan(span));
    }

    /** Runs {@code work} in a child span of the current one. */
    public <T> T child(String name, String source, Supplier<T> work) {
        Span span = tracer.nextSpan().name(name).tag("source", source).start();
        try (Tracer.SpanInScope _ = tracer.withSpan(span)) {
            return work.get();
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    public void child(String name, String source, Runnable work) {
        child(name, source, () -> {
            work.run();
            return null;
        });
    }

    /** The context of the span in scope, for work that continues on another thread; {@code null} without one. */
    public @Nullable TraceContext currentContext() {
        Span span = tracer.currentSpan();
        if (span == null || span.context() == TraceContext.NOOP) {
            return null;
        }
        return span.context();
    }

    /** Starts a child span that the caller ends, e.g. around a commit that happens outside a callback. */
    public Span startChild(String name) {
        return tracer.nextSpan().name(name).start();
    }

    @Nullable
    TraceContext producerContext(@Nullable String traceparent) {
        if (traceparent == null) {
            return null;
        }
        Matcher m = TRACEPARENT.matcher(traceparent.strip());
        if (!m.matches()) {
            return null;
        }
        return tracer.traceContextBuilder()
                .traceId(m.group(1))
                .spanId(m.group(2))
                .sampled((Integer.parseInt(m.group(3), 16) & 1) == 1)
                .build();
    }

    /** The poll span and its scope. */
    public record Poll(Span span, Tracer.SpanInScope scope) implements AutoCloseable {

        public void outcome(String outcome) {
            span.tag("outcome", outcome);
        }

        public void error(Throwable error) {
            span.tag("outcome", "error");
            span.error(error);
        }

        @Override
        public void close() {
            scope.close();
            span.end();
        }
    }
}
