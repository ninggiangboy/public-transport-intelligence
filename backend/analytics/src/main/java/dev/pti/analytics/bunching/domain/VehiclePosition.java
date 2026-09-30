package dev.pti.analytics.bunching.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One row of {@code dw.fact_vehicle_position} as bunching detection reads it (DOC-23 §5.6). The stop the vehicle is at
 * or heading to is {@code currentStopSequence} in the pattern of {@code tripId}.
 *
 * @param serviceDate the date the trip started, which picks the day type and the hour of the service day
 */
public record VehiclePosition(
        String vehicleId,
        Instant eventTimestamp,
        LocalDate serviceDate,
        String tripId,
        int directionId,
        double lat,
        double lon,
        int currentStopSequence,
        StopStatus status) {}
