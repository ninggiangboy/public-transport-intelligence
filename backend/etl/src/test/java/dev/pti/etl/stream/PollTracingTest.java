package dev.pti.etl.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.pti.etl.core.EtlSource;
import dev.pti.etl.core.InboundMessage;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** DOC-28 O-04: {@code pti.etl.poll} is a root span linked to the producer spans of its records. */
class PollTracingTest {

    private static final String TRACE_A = "0af7651916cd43dd8448eb211c80319c";
    private static final String SPAN_A = "b7ad6b7169203331";
    private static final String TRACE_B = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_B = "00f067aa0ba902b7";

    private final List<SpanData> spans = new CopyOnWriteArrayList<>();
    private final SdkTracerProvider provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(new Collector(spans)))
            .build();
    private final OtelTracer tracer = new OtelTracer(provider.get("test"), new OtelCurrentTraceContext(), event -> {});

    @AfterEach
    void close() {
        provider.close();
    }

    @Test
    void thePollIsARootLinkedToEachDistinctProducerSpan() {
        PollTracing tracing = new PollTracing(tracer, 20);
        StreamChunkRequest request = request(
                traceparent(TRACE_A, SPAN_A, "01"),
                traceparent(TRACE_A, SPAN_A, "01"),
                traceparent(TRACE_B, SPAN_B, "00"),
                "not a traceparent",
                null);

        try (PollTracing.Poll poll = tracing.poll(request)) {
            tracing.child("pti.etl.write", "GTFS_RT_VEHICLE_POSITION", () -> {});
            poll.outcome("completed");
        }

        SpanData root = span("pti.etl.poll");
        assertThat(root.getParentSpanContext().isValid()).isFalse();
        assertThat(root.getLinks())
                .extracting(LinkData::getSpanContext)
                .extracting(SpanContext::getTraceId, SpanContext::getSpanId)
                .containsExactlyInAnyOrder(tuple(TRACE_A, SPAN_A), tuple(TRACE_B, SPAN_B));
        assertThat(root.getAttributes().asMap().toString()).contains("GTFS_RT_VEHICLE_POSITION", "completed");
        SpanData write = span("pti.etl.write");
        assertThat(write.getTraceId()).isEqualTo(root.getTraceId());
        assertThat(write.getParentSpanId()).isEqualTo(root.getSpanId());
    }

    @Test
    void linksAtMostTheConfiguredNumberOfProducers() {
        PollTracing tracing = new PollTracing(tracer, 2);
        List<String> headers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            headers.add(traceparent(TRACE_A, "%016x".formatted(i + 1), "01"));
        }

        try (PollTracing.Poll _ = tracing.poll(request(headers.toArray(String[]::new)))) {
            // nothing else in the poll
        }

        assertThat(span("pti.etl.poll").getLinks()).hasSize(2);
    }

    private SpanData span(String name) {
        return spans.stream()
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No span " + name + " in " + spans));
    }

    private static String traceparent(String traceId, String spanId, String flags) {
        return "00-" + traceId + "-" + spanId + "-" + flags;
    }

    private static StreamChunkRequest request(String... traceparents) {
        List<InboundMessage> messages = new ArrayList<>();
        long offset = 0;
        for (String header : traceparents) {
            messages.add(new InboundMessage(
                    EtlSource.GTFS_RT_VEHICLE_POSITION,
                    "18",
                    new byte[0],
                    "gtfs.vehicle_positions",
                    0,
                    offset++,
                    null,
                    header == null ? Map.of() : Map.of(PollTracing.TRACEPARENT_HEADER, header)));
        }
        return new StreamChunkRequest(
                UUID.randomUUID(),
                EtlSource.GTFS_RT_VEHICLE_POSITION,
                "gtfs-rt-vehicle-position",
                "pti-etl-gtfs-rt",
                "test",
                messages,
                false);
    }

    private record Collector(List<SpanData> spans) implements SpanExporter {

        @Override
        public CompletableResultCode export(Collection<SpanData> batch) {
            spans.addAll(batch);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
