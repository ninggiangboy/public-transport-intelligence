package dev.pti.etl.core.cdc;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** An unwrapped Debezium event of {@code sale_point} (DOC-09 §5.2). */
public record SalePointCdc(
        @JsonProperty("sale_point_id") @NotBlank @Pattern(regexp = "^(KIOSK|ONBOARD|APP)-[0-9A-Z]{1,16}$")
        String salePointId,

        @JsonProperty("name") @NotBlank @Size(max = 200) String name,

        @JsonProperty("kind") @NotNull @Pattern(regexp = "^(KIOSK|ONBOARD|APP)$")
        String kind,

        @JsonProperty("stop_id") @Nullable @Size(max = 64) String stopId,
        @JsonProperty("route_id") @Nullable @Size(max = 64) String routeId,
        @JsonProperty("created_at") @Nullable Instant createdAt,
        @JsonProperty("updated_at") @Nullable Instant updatedAt,

        @JsonProperty("__op") @NotNull @Pattern(regexp = "^[rcud]$")
        String op,

        @JsonProperty("__lsn") @NotNull @PositiveOrZero Long lsn,
        @JsonProperty("__source_ts_ms") @NotNull Long sourceTsMs,

        @JsonProperty("__deleted") @Nullable @Pattern(regexp = "^(true|false)$")
        String deleted) {

    public boolean isDelete() {
        return "d".equals(op) || "true".equals(deleted);
    }
}
