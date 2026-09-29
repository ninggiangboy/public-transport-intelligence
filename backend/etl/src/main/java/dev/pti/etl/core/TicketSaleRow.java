package dev.pti.etl.core;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** One row of {@code dw.fact_ticket_sales} (DOC-13 §7.3). {@code op} is the Debezium operation. */
public record TicketSaleRow(
        LocalDate saleDate,
        UUID transactionId,
        String salePointId,
        @Nullable String routeId,
        @Nullable String stopId,
        String ticketType,
        String txnType,
        BigDecimal amount,
        String currency,
        @Nullable UUID refundOf,
        String status,
        boolean deleted,
        Instant createdAt,
        Instant sourceUpdatedAt,
        long sourceLsn,
        Instant eventTimestamp,
        String payloadHash,
        String op) {

    public boolean isRefund() {
        return "REFUND".equals(txnType);
    }
}
