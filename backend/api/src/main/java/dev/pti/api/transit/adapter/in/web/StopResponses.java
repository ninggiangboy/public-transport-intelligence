package dev.pti.api.transit.adapter.in.web;

import dev.pti.api.transit.domain.Stop;
import dev.pti.api.transit.domain.StopDetail;
import dev.pti.api.transit.domain.StopDisruption;
import dev.pti.api.transit.domain.StopMatch;
import dev.pti.api.transit.domain.StopRoute;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The response bodies of {@code GET /stops} and {@code GET /stops/{stopId}} (DOC-32 E-06, E-07). */
final class StopResponses {

    private StopResponses() {}

    /** One stop of the search, with the routes that call at it. */
    public record StopItemResponse(
            String stopId,
            @Nullable String code,
            String name,
            double lat,
            double lon,
            int locationType,
            int wheelchairBoarding,
            List<String> routeIds) {

        static StopItemResponse from(StopMatch match) {
            Stop stop = match.stop();
            return new StopItemResponse(
                    stop.stopId(),
                    stop.code(),
                    stop.name(),
                    stop.lat(),
                    stop.lon(),
                    stop.locationType(),
                    stop.wheelchairBoarding(),
                    match.routeIds());
        }
    }

    /** A stop with its routes and the disruptions open on them. */
    public record StopDetailResponse(
            String stopId,
            @Nullable String code,
            String name,
            double lat,
            double lon,
            int locationType,
            int wheelchairBoarding,
            List<StopRouteResponse> routes,
            List<StopDisruptionResponse> activeDisruptions) {

        static StopDetailResponse from(StopDetail detail) {
            Stop stop = detail.stop();
            return new StopDetailResponse(
                    stop.stopId(),
                    stop.code(),
                    stop.name(),
                    stop.lat(),
                    stop.lon(),
                    stop.locationType(),
                    stop.wheelchairBoarding(),
                    detail.routes().stream().map(StopRouteResponse::from).toList(),
                    detail.activeDisruptions().stream()
                            .map(StopDisruptionResponse::from)
                            .toList());
        }
    }

    /** A route that calls at the stop. */
    public record StopRouteResponse(
            String routeId,
            String displayName,
            @Nullable String color,
            @Nullable String textColor,
            List<String> headsigns) {

        static StopRouteResponse from(StopRoute route) {
            return new StopRouteResponse(
                    route.routeId(), route.displayName(), route.color(), route.textColor(), route.headsigns());
        }
    }

    /** An open disruption alert on one of those routes. */
    public record StopDisruptionResponse(
            String alertId,
            @Nullable String disruptionId,
            String routeId,
            @Nullable Integer directionId,
            int severity,
            String title,
            @Nullable String startedAt) {

        static StopDisruptionResponse from(StopDisruption disruption) {
            return new StopDisruptionResponse(
                    disruption.alertId(),
                    disruption.disruptionId(),
                    disruption.routeId(),
                    disruption.directionId(),
                    disruption.severity(),
                    disruption.title(),
                    TransitParams.instant(disruption.startedAt()));
        }
    }
}
