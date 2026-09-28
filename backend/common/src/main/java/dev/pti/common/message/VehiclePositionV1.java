package dev.pti.common.message;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** VehiclePosition payload, version 1 (DOC-09 §3.1). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record VehiclePositionV1(
        @NotNull @Size(min = 1, max = 64) String vehicleId,
        @NotNull @Size(min = 1, max = 128) String tripId,
        @NotNull @Size(min = 1, max = 64) String routeId,
        @NotNull @Min(0) @Max(1) Integer directionId,
        @NotNull @Pattern(regexp = "^[0-9]{8}$") String startDate,
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double lon,
        @Nullable @DecimalMin("0") @DecimalMax("360") Double bearing,
        @Nullable @DecimalMin("0") Double speedMps,
        @NotNull @Min(0) Integer currentStopSequence,
        @NotNull @Size(min = 1, max = 64) String stopId,
        @NotNull VehicleStopStatus currentStatus)
        implements VehiclePosition {}
