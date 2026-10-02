package dev.pti.api.stream.domain;

/** How a connection's {@code Last-Event-ID} was served (DOC-26 §12, {@code pti_api_sse_connections_opened_total}). */
public enum ReplayKind {
    NONE,
    EXACT,
    TIME,
    RESYNC
}
