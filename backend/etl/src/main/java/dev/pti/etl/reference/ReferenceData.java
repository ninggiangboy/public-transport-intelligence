package dev.pti.etl.reference;

import dev.pti.common.gtfs.GtfsTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Immutable snapshot of the ACTIVE feed (DOC-21 §6), read by the realtime DQ rules and the TripUpdate mapping. It
 * is replaced as a whole when the feed changes; a chunk keeps the snapshot it started with (DOC-16 §7).
 */
public final class ReferenceData {

    private final long feedVersionId;
    private final ZoneId agencyZone;
    private final BoundingBox bbox;
    private final Set<String> routes;
    private final Set<String> stops;
    private final Map<String, TripRef> trips;
    private final ServiceCalendar calendar;
    private final StopTimes stopTimes;

    public ReferenceData(
            long feedVersionId,
            ZoneId agencyZone,
            BoundingBox bbox,
            Set<String> routes,
            Set<String> stops,
            Map<String, TripRef> trips,
            ServiceCalendar calendar,
            StopTimes stopTimes) {
        this.feedVersionId = feedVersionId;
        this.agencyZone = agencyZone;
        this.bbox = bbox;
        this.routes = Set.copyOf(routes);
        this.stops = Set.copyOf(stops);
        this.trips = Map.copyOf(trips);
        this.calendar = calendar;
        this.stopTimes = stopTimes;
    }

    public long feedVersionId() {
        return feedVersionId;
    }

    public ZoneId agencyZone() {
        return agencyZone;
    }

    public BoundingBox bbox() {
        return bbox;
    }

    public boolean hasRoute(String routeId) {
        return routes.contains(routeId);
    }

    public boolean hasStop(String stopId) {
        return stops.contains(stopId);
    }

    public Optional<TripRef> trip(String tripId) {
        return Optional.ofNullable(trips.get(tripId));
    }

    public boolean runsOn(String serviceId, LocalDate serviceDate) {
        return calendar.serviceIdsOn(serviceDate).contains(serviceId);
    }

    /** Scheduled arrival at {@code (trip, stop_sequence)} on a service date, or {@code null} when unknown. */
    public @Nullable Instant scheduledArrival(String tripId, int stopSequence, LocalDate serviceDate) {
        TripRef trip = trips.get(tripId);
        if (trip == null || !runsOn(trip.serviceId(), serviceDate)) {
            return null;
        }
        Integer seconds = stopTimes.arrivalSeconds(tripId, stopSequence, serviceDate);
        return seconds == null ? null : GtfsTime.toInstant(serviceDate, seconds, agencyZone);
    }

    /** Loads the stop times of a service date ahead of the first chunk that needs them. */
    public void warm(LocalDate serviceDate) {
        stopTimes.warm(serviceDate);
    }
}
