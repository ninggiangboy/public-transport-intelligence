package dev.pti.etl.core.domain;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An unwrapped Debezium event of {@code ticket_transaction} (DOC-09 §5.2, DOC-16 §2.2). {@code customer_ref} is
 * dropped while reading, so personal data never goes further (DR-60).
 */
public record TicketTransactionCdc(
        UUID transactionId,
        String salePointId,
        @Nullable String routeId,
        @Nullable String stopId,
        String ticketType,
        String txnType,
        String amount,
        String currency,
        @Nullable UUID refundOf,
        String status,
        Instant createdAt,
        Instant updatedAt,
        String op,
        Long lsn,
        Long sourceTsMs,
        @Nullable String deleted) {

    /** A delete event carries the row as it was before the delete (REPLICA IDENTITY FULL). */
    public boolean isDelete() {
        return "d".equals(op) || "true".equals(deleted);
    }
}
