package dev.pti.simulator.feed;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The GTFS feed in memory (DOC-25 §4.1). Immutable once loaded. */
public final class Feed {

    private final String sha256;
    private final ZoneId zone;
    private final Map<String, Route> routes;
    private final Map<String, Stop> stops;
    private final Map<String, TripSchedule> trips;
    private final ServiceCalendar calendar;
    private final List<String> vehicleIds;

    Feed(
            String sha256,
            ZoneId zone,
            Map<String, Route> routes,
            Map<String, Stop> stops,
            Map<String, TripSchedule> trips,
            ServiceCalendar calendar,
            List<String> vehicleIds) {
        this.sha256 = sha256;
        this.zone = zone;
        this.routes = Map.copyOf(routes);
        this.stops = Map.copyOf(stops);
        this.trips = Map.copyOf(trips);
        this.calendar = calendar;
        this.vehicleIds = vehicleIds.stream().sorted(Comparator.naturalOrder()).toList();
    }

    public String sha256() {
        return sha256;
    }

    /** The agency time zone ({@code America/Chicago}). */
    public ZoneId zone() {
        return zone;
    }

    public Map<String, Route> routes() {
        return routes;
    }

    public Optional<Route> route(String routeId) {
        return Optional.ofNullable(routes.get(routeId));
    }

    public Map<String, Stop> stops() {
        return stops;
    }

    public Map<String, TripSchedule> trips() {
        return trips;
    }

    public ServiceCalendar calendar() {
        return calendar;
    }

    public LocalDate validFrom() {
        return calendar.validFrom();
    }

    public LocalDate validTo() {
        return calendar.validTo();
    }

    /** {@code vehicles.txt} ids in string order (DOC-13 §4). */
    public List<String> vehicleIds() {
        return vehicleIds;
    }
}
