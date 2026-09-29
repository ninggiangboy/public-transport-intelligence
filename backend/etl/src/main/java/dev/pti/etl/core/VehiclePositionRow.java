package dev.pti.etl.core;

import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/** One row of {@code dw.fact_vehicle_position}, also the source of {@code vehicle_position_latest} (DOC-13 §7.1). */
public record VehiclePositionRow(
        LocalDate serviceDate,
        String vehicleId,
        Instant eventTimestamp,
        String tripId,
        String routeId,
        short directionId,
        double lat,
        double lon,
        @Nullable Float bearing,
        @Nullable Float speedMps,
        int currentStopSequence,
        String stopId,
        String currentStatus,
        @Nullable String occupancyStatus,
        short schemaVersion,
        String payloadHash) {}
