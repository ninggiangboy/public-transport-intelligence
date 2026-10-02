package dev.pti.api.stream.domain;

/** The {@code reason} of a {@code resync} frame (DOC-33 §5.9). */
public enum ResyncReason {
    /** {@code Last-Event-ID} is older than the ring buffer, or not recognised. */
    BUFFER_EXPIRED,
    /** The connection's queue filled up and its pending frames were dropped. */
    SLOW_CLIENT,
    /** The consumer was assigned its partitions again and the buffer may have a gap. */
    CONSUMER_RESET
}
