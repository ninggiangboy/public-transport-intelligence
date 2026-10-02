package dev.pti.api.stream.domain;

/** Why a connection ended (DOC-26 §3), the label of {@code pti_api_sse_connections_closed_total}. */
public enum CloseReason {
    CLIENT_GONE,
    TIMEOUT,
    TOKEN_EXPIRED,
    WRITE_STALLED,
    SHUTDOWN
}
