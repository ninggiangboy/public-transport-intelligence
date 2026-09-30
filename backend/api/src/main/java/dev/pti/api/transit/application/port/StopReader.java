package dev.pti.api.transit.application.port;

import dev.pti.api.platform.domain.ActiveFeed;
import dev.pti.api.transit.domain.BoundingBox;
import dev.pti.api.transit.domain.Stop;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** The stops and stations of a feed version (DOC-32 E-06, E-07): {@code location_type} 0 and 1 only. */
public interface StopReader {

    /**
     * What the map and route filters of the stop search ask for.
     *
     * @param bbox {@code null} for no window
     * @param routeId the route filter, {@code null} for none; it is what the cache of the result is keyed by
     * @param routeStopIds the stops of {@code routeId}, {@code null} when there is no route filter
     * @param afterStopId the last stop of the previous page, {@code null} for the first page
     * @param fetchSize how many stops to read: the page size plus one
     */
    record AreaQuery(
            @Nullable BoundingBox bbox,
            @Nullable String routeId,
            @Nullable List<String> routeStopIds,
            @Nullable String afterStopId,
            int fetchSize) {}

    Optional<Stop> find(ActiveFeed feed, String stopId);

    /**
     * Stops whose code is {@code q} or whose name contains it, code match first, then name prefix, then the rest;
     * each group by name and stop id. {@code q} is plain text: the implementation treats {@code %} and {@code _} as
     * ordinary characters.
     */
    List<Stop> searchText(ActiveFeed feed, String q, int limit);

    /** Stops inside the window and/or of the route, in stop id order after {@code afterStopId}. */
    List<Stop> searchArea(ActiveFeed feed, AreaQuery query);
}
