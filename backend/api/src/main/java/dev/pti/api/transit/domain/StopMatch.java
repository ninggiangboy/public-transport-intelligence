package dev.pti.api.transit.domain;

import java.util.List;

/** A stop found by the search with the routes that call at it (DOC-32 E-06). */
public record StopMatch(Stop stop, List<String> routeIds) {

    public StopMatch {
        routeIds = List.copyOf(routeIds);
    }
}
