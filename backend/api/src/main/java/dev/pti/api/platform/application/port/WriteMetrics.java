package dev.pti.api.platform.application.port;

/**
 * Counts the writes of operators into {@code pti_api_write_requests_total{operation, outcome}} (DOC-31 §15). The
 * {@code operation} is one of the names of the table there ({@code ack}, {@code feedback}, {@code flag}, …), the
 * {@code outcome} one of {@code created}, {@code idempotent} or {@code rejected}.
 */
public interface WriteMetrics {

    String CREATED = "created";
    String IDEMPOTENT = "idempotent";
    String REJECTED = "rejected";

    void recorded(String operation, String outcome);
}
