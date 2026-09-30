package dev.pti.api.transit.domain;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** A route that calls at a stop, as the stop page lists it (DOC-32 E-07). */
public record StopRoute(
        String routeId,
        String displayName,
        @Nullable String color,
        @Nullable String textColor,
        List<String> headsigns) {

    public StopRoute {
        headsigns = List.copyOf(headsigns);
    }
}
