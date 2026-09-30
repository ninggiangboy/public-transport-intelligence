package dev.pti.api.transit.domain;

import java.time.Instant;
import java.util.List;

/** The next calls at a stop (DOC-32 E-08). */
public record StopArrivals(String stopId, Instant businessNow, boolean realtimeEnabled, List<Arrival> arrivals) {

    public StopArrivals {
        arrivals = List.copyOf(arrivals);
    }
}
