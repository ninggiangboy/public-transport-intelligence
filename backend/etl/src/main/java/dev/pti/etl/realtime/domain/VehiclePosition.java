package dev.pti.etl.realtime.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The latest position of one vehicle as {@code vehicles.batch} carries it (DOC-33 §5.1). */
public record VehiclePosition(
        String routeId,
        String vehicleId,
        String tripId,
        int directionId,
        double lat,
        double lon,
        @Nullable Float bearing,
        @Nullable Float speedMps,
        String currentStatus,
        String stopId,
        int currentStopSequence,
        @Nullable String occupancyStatus,
        Instant eventTimestamp) {

    /** The element of {@code vehicles}; absent values are left out, as the API does (DOC-31 §3). */
    public Map<String, Object> toData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("vehicleId", vehicleId);
        data.put("tripId", tripId);
        data.put("directionId", directionId);
        data.put("lat", lat);
        data.put("lon", lon);
        if (bearing != null) {
            data.put("bearing", bearing);
        }
        if (speedMps != null) {
            data.put("speedMps", speedMps);
        }
        data.put("currentStatus", currentStatus);
        data.put("stopId", stopId);
        data.put("currentStopSequence", currentStopSequence);
        if (occupancyStatus != null) {
            data.put("occupancyStatus", occupancyStatus);
        }
        data.put("eventTimestamp", eventTimestamp.toString());
        return data;
    }
}
