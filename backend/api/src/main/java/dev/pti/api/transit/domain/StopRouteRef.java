package dev.pti.api.transit.domain;

import java.util.List;

/** A route that serves a stop, with the headsigns of its trips there, sorted (DOC-32 E-07). */
public record StopRouteRef(String routeId, List<String> headsigns) {

    public StopRouteRef {
        headsigns = List.copyOf(headsigns);
    }
}
