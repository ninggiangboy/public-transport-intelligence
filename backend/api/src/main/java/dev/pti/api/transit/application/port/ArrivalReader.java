package dev.pti.api.transit.application.port;

import dev.pti.api.platform.domain.ActiveFeed;
import dev.pti.api.transit.domain.ArrivalCandidate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** The scheduled calls at a stop around now, with the data that predicts them (DOC-23 §7.4, DOC-32 E-08). */
public interface ArrivalReader {

    /**
     * Calls of the service dates of yesterday and today whose scheduled arrival is in {@code [now - 30 min, now +
     * horizon]}, on trips that run that day and allow pickup, each with the ETA row and the trip update of the stop.
     */
    List<ArrivalCandidate> candidates(ActiveFeed feed, String stopId, Instant now, Duration horizon);
}
