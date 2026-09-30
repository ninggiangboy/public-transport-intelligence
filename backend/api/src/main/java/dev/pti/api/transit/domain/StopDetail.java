package dev.pti.api.transit.domain;

import java.util.List;

/** A stop, the routes that call at it and the disruptions going on at them (DOC-32 E-07). */
public record StopDetail(Stop stop, List<StopRoute> routes, List<StopDisruption> activeDisruptions) {

    public StopDetail {
        routes = List.copyOf(routes);
        activeDisruptions = List.copyOf(activeDisruptions);
    }
}
