package dev.pti.api.transit.domain;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The pattern of one direction of a route (DOC-32 E-02): the most common shape, its line, and the stops of the
 * representative trip in order. {@code label} and {@code headsign} are the most frequent values among the trips of the
 * shape.
 */
public record DirectionPattern(
        int directionId,
        @Nullable String label,
        @Nullable String headsign,
        int tripCount,
        @Nullable String shapeId,
        GeometrySource geometrySource,
        List<GeoPoint> geometry,
        List<PatternStop> stops) {

    public DirectionPattern {
        geometry = List.copyOf(geometry);
        stops = List.copyOf(stops);
    }
}
