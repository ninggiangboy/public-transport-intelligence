package dev.pti.common.message;

import dev.pti.common.gtfs.GtfsTime;
import dev.pti.common.time.Timestamps;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The only place that builds business-key strings (DOC-13 §6.2). The ledger and the experiment runner compare
 * warehouse rows with these strings, so the format must never change.
 */
public final class BusinessKey {

    private BusinessKey() {}

    /** {@code <vehicle_id>|<event_timestamp>}, e.g. {@code 2050|2026-09-29T21:19:05.000Z}. */
    public static String vehiclePosition(String vehicleId, Instant eventTimestamp) {
        return vehicleId + "|" + Timestamps.format(eventTimestamp);
    }

    /** {@code <service_date YYYY-MM-DD>|<trip_id>|<stop_sequence>}, e.g. {@code 2026-09-29|1361959|14}. */
    public static String tripUpdate(LocalDate serviceDate, String tripId, int stopSequence) {
        return serviceDate + "|" + tripId + "|" + stopSequence;
    }

    /** {@code <sale_date YYYY-MM-DD>|<transaction_id>}. */
    public static String ticketSale(LocalDate saleDate, UUID transactionId) {
        return saleDate + "|" + transactionId;
    }

    /** The keys of one message: one for a VehiclePosition, one per stop for a TripUpdate. */
    public static List<String> of(Envelope<?> envelope) {
        return switch (envelope.payload()) {
            case VehiclePosition vp -> List.of(vehiclePosition(vp.vehicleId(), envelope.eventTimestamp()));
            case TripUpdate tu -> {
                LocalDate serviceDate = GtfsTime.parseServiceDate(tu.startDate());
                yield tu.stopTimeUpdates().stream()
                        .map(u -> tripUpdate(serviceDate, tu.tripId(), u.stopSequence()))
                        .toList();
            }
        };
    }
}
