package dev.pti.api.transit.application.port;

import dev.pti.api.platform.domain.ActiveFeed;
import dev.pti.api.transit.domain.LiveVehicle;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** The latest position of every vehicle that reported recently (DOC-32 E-05). */
public interface LiveVehicleReader {

    /**
     * @param routeIds the routes to keep; every route when empty
     * @param maxAge vehicles whose last position is older than {@code now - maxAge} are left out
     * @param limit at most this many vehicles, the most recently reported first
     */
    List<LiveVehicle> snapshot(ActiveFeed feed, Set<String> routeIds, Instant now, Duration maxAge, int limit);
}
