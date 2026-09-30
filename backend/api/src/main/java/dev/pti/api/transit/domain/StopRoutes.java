package dev.pti.api.transit.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which routes serve which stops in one feed version: built once per feed from the stop times (DOC-32 E-06) and read
 * for the {@code routeIds} of a stop, the {@code routeId} filter of the stop search and the routes of a stop.
 */
public final class StopRoutes {

    private final Map<String, List<StopRouteRef>> byStop;
    private final Map<String, List<String>> stopsByRoute;

    public StopRoutes(Map<String, List<StopRouteRef>> byStop) {
        this.byStop = Map.copyOf(byStop);
        Map<String, List<String>> inverse = new HashMap<>();
        byStop.forEach(
                (stopId, refs) -> refs.forEach(ref -> inverse.computeIfAbsent(ref.routeId(), route -> new ArrayList<>())
                        .add(stopId)));
        inverse.replaceAll((routeId, stops) -> List.copyOf(stops));
        this.stopsByRoute = Map.copyOf(inverse);
    }

    public List<StopRouteRef> routesOf(String stopId) {
        return byStop.getOrDefault(stopId, List.of());
    }

    public List<String> routeIdsOf(String stopId) {
        return routesOf(stopId).stream().map(StopRouteRef::routeId).toList();
    }

    /** The stops that at least one trip of the route calls at; empty for a route that is not in the feed. */
    public List<String> stopsOf(String routeId) {
        return stopsByRoute.getOrDefault(routeId, List.of());
    }
}
