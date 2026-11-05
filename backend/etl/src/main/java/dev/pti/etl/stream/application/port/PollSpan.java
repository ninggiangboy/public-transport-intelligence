package dev.pti.etl.stream.application.port;

/**
 * The trace identity of the {@code pti.etl.poll} span, plain enough for {@code MicroBatchCommitted} to carry it to
 * analytics and the UI feed (DOC-23 §14.3): the tracing adapter turns it back into a link on the consumer side.
 */
public record PollSpan(String traceId, String spanId, boolean sampled) {}
