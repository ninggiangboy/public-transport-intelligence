package dev.pti.etl.rules;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The fields of a VehiclePosition or a TripUpdate that the realtime rules DQ-03 to DQ-09 read (DOC-16 §2). For a
 * TripUpdate, {@code stopIds} and {@code delays} cover every stop time update, so one bad stop rejects the message.
 */
public record RealtimeFacts(
        String routeId,
        String tripId,
        short directionId,
        List<String> stopIds,
        LocalDate serviceDate,
        Instant eventTimestamp,
        @Nullable Double lat,
        @Nullable Double lon,
        List<Integer> delays) {

    public RealtimeFacts {
        stopIds = List.copyOf(stopIds);
        delays = List.copyOf(delays);
    }
}
