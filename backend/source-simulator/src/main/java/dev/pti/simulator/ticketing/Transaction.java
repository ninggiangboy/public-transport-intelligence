package dev.pti.simulator.ticketing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A row of {@code ticket_transaction} (DOC-25 §9.3).
 *
 * @param refundOf the refunded sale; {@code null} for a sale
 * @param createdAt business time (DOC-25 §3.1), set explicitly rather than by {@code DEFAULT now()}
 */
public record Transaction(
        UUID id,
        String salePointId,
        @Nullable String routeId,
        @Nullable String stopId,
        TicketType ticketType,
        BigDecimal amount,
        @Nullable UUID refundOf,
        @Nullable String customerRef,
        Instant createdAt) {

    public boolean isRefund() {
        return refundOf != null;
    }

    /** The refund of this sale at {@code at}: same sale point, ticket and amount (DOC-25 §9.3). */
    public Transaction refund(UUID refundId, Instant at) {
        return new Transaction(refundId, salePointId, routeId, stopId, ticketType, amount, id, customerRef, at);
    }
}
