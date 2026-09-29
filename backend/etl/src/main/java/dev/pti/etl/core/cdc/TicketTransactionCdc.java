package dev.pti.etl.core.cdc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An unwrapped Debezium event of {@code ticket_transaction} (DOC-09 §5.2, DOC-16 §2.2). {@code customer_ref} is
 * dropped while reading, so personal data never goes further (DR-60); any other unknown field is a schema error.
 */
@JsonIgnoreProperties({"customer_ref"})
public record TicketTransactionCdc(
        @JsonProperty("transaction_id") @NotNull UUID transactionId,

        @JsonProperty("sale_point_id") @NotBlank @Pattern(regexp = "^(KIOSK|ONBOARD|APP)-[0-9A-Z]{1,16}$")
        String salePointId,

        @JsonProperty("route_id") @Nullable @Size(max = 64) String routeId,
        @JsonProperty("stop_id") @Nullable @Size(max = 64) String stopId,

        @JsonProperty("ticket_type") @NotNull @Pattern(regexp = "^(SINGLE|DAY|MONTH)$")
        String ticketType,

        @JsonProperty("txn_type") @NotNull @Pattern(regexp = "^(SALE|REFUND)$")
        String txnType,

        @JsonProperty("amount") @NotNull @Pattern(regexp = "^-?[0-9]{1,8}(\\.[0-9]{1,2})?$")
        String amount,

        @JsonProperty("currency") @NotBlank @Size(min = 3, max = 3)
        String currency,

        @JsonProperty("refund_of") @Nullable UUID refundOf,

        @JsonProperty("status") @NotNull @Pattern(regexp = "^(COMPLETED|VOIDED)$")
        String status,

        @JsonProperty("created_at") @NotNull Instant createdAt,
        @JsonProperty("updated_at") @NotNull Instant updatedAt,

        @JsonProperty("__op") @NotNull @Pattern(regexp = "^[rcud]$")
        String op,

        @JsonProperty("__lsn") @NotNull @PositiveOrZero Long lsn,
        @JsonProperty("__source_ts_ms") @NotNull Long sourceTsMs,

        @JsonProperty("__deleted") @Nullable @Pattern(regexp = "^(true|false)$")
        String deleted) {

    /** A delete event carries the row as it was before the delete (REPLICA IDENTITY FULL). */
    public boolean isDelete() {
        return "d".equals(op) || "true".equals(deleted);
    }
}
