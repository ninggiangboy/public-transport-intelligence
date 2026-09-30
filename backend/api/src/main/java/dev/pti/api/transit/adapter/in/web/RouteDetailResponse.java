package dev.pti.api.transit.adapter.in.web;

import dev.pti.api.transit.domain.DirectionPattern;
import dev.pti.api.transit.domain.GeoPoint;
import dev.pti.api.transit.domain.PatternStop;
import dev.pti.api.transit.domain.RouteDetail;
import dev.pti.api.transit.domain.RouteSummary;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Response of {@code GET /routes/{routeId}} (DOC-32 E-02). */
public record RouteDetailResponse(
        String routeId,
        long feedVersionId,
        @Nullable String shortName,
        @Nullable String longName,
        String displayName,
        int routeType,
        @Nullable String color,
        @Nullable String textColor,
        @Nullable Integer typicalHeadwaySeconds,
        List<DirectionResponse> directions) {

    /** One direction: its line as GeoJSON and its stops in order. */
    public record DirectionResponse(
            int directionId,
            @Nullable String label,
            @Nullable String headsign,
            int tripCount,
            @Nullable String shapeId,
            String geometrySource,
            LineStringResponse geometry,
            List<PatternStopResponse> stops) {

        static DirectionResponse from(DirectionPattern direction) {
            return new DirectionResponse(
                    direction.directionId(),
                    direction.label(),
                    direction.headsign(),
                    direction.tripCount(),
                    direction.shapeId(),
                    direction.geometrySource().name(),
                    LineStringResponse.from(direction.geometry()),
                    direction.stops().stream().map(PatternStopResponse::from).toList());
        }
    }

    /** A GeoJSON {@code LineString}: coordinates are {@code [lon, lat]}. */
    public record LineStringResponse(String type, List<double[]> coordinates) {

        static LineStringResponse from(List<GeoPoint> points) {
            return new LineStringResponse(
                    "LineString",
                    points.stream()
                            .map(point -> new double[] {point.lon(), point.lat()})
                            .toList());
        }
    }

    /** A stop of the direction. */
    public record PatternStopResponse(
            String stopId, @Nullable String code, String name, double lat, double lon, int stopSequence) {

        static PatternStopResponse from(PatternStop stop) {
            return new PatternStopResponse(
                    stop.stopId(), stop.code(), stop.name(), stop.lat(), stop.lon(), stop.stopSequence());
        }
    }

    static RouteDetailResponse from(RouteDetail detail) {
        RouteSummary route = detail.route();
        return new RouteDetailResponse(
                route.routeId(),
                detail.feedVersionId(),
                route.shortName(),
                route.longName(),
                route.displayName(),
                route.routeType(),
                route.color(),
                route.textColor(),
                route.typicalHeadwaySeconds(),
                detail.directions().stream().map(DirectionResponse::from).toList());
    }
}
