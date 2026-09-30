package dev.pti.api.transit.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** The newest position of a vehicle and the prediction at the stop it is heading for (DOC-32 E-05). */
public record LiveVehicle(
        String vehicleId,
        @Nullable String label,
        String routeId,
        String tripId,
        int directionId,
        @Nullable String headsign,
        double lat,
        double lon,
        @Nullable Float bearing,
        @Nullable Float speedMps,
        String currentStatus,
        String stopId,
        int currentStopSequence,
        @Nullable String occupancyStatus,
        Instant eventTimestamp,
        @Nullable Integer delaySeconds,
        @Nullable Instant stopArrivalAt) {}
