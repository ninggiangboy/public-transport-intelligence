package dev.pti.analytics.reference.domain;

import java.util.Map;

/**
 * A route as analytics needs it (DOC-23 §3).
 *
 * @param routeType the GTFS {@code route_type}; detectors filter on it (FR-05.2)
 * @param label {@code route_short_name}, or the route id when that is empty; used in alert titles
 * @param directionLabels the most common {@code direction_label} of the trips of each direction
 */
public record RouteInfo(String routeId, int routeType, String label, Map<Integer, String> directionLabels) {

    public RouteInfo {
        directionLabels = Map.copyOf(directionLabels);
    }

    /** The label of a direction, {@code Direction 0} or {@code Direction 1} when the feed names none. */
    public String directionLabel(int directionId) {
        return directionLabels.getOrDefault(directionId, "Direction " + directionId);
    }
}
