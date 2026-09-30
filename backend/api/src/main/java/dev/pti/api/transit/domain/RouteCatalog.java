package dev.pti.api.transit.domain;

import java.util.List;
import java.util.Set;

/** The routes of one feed version, in the order of the route picker (DOC-32 E-01). */
public record RouteCatalog(long feedVersionId, List<RouteSummary> routes) {

    public RouteCatalog {
        routes = List.copyOf(routes);
    }

    /** The routes of the given GTFS {@code route_type}s; every route when {@code routeTypes} is empty. */
    public RouteCatalog ofTypes(Set<Integer> routeTypes) {
        if (routeTypes.isEmpty()) {
            return this;
        }
        return new RouteCatalog(
                feedVersionId,
                routes.stream()
                        .filter(route -> routeTypes.contains(route.routeType()))
                        .toList());
    }

    public boolean contains(String routeId) {
        return routes.stream().anyMatch(route -> route.routeId().equals(routeId));
    }
}
