package dev.pti.etl.core;

import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/** One row of {@code dw.fact_trip_update}: one stop of one TripUpdate (DR-13, DOC-20 §4.3). */
public record TripUpdateRow(
        LocalDate serviceDate,
        String tripId,
        int stopSequence,
        String routeId,
        short directionId,
        String stopId,
        String vehicleId,
        String scheduleRelationship,
        @Nullable Instant scheduledArrival,
        @Nullable Instant arrivalTime,
        @Nullable Instant departureTime,
        @Nullable Integer delaySeconds,
        boolean observed,
        Instant eventTimestamp,
        String payloadHash) {

    /** Primary key of the row, {@code (service_date, trip_id, stop_sequence)}. */
    public String key() {
        return serviceDate + "|" + tripId + "|" + stopSequence;
    }
}
