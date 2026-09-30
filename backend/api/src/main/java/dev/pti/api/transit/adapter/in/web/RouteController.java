package dev.pti.api.transit.adapter.in.web;

import dev.pti.api.platform.adapter.in.web.ApiPaths;
import dev.pti.api.platform.adapter.in.web.DataAsOfHeader;
import dev.pti.api.platform.domain.WithAsOf;
import dev.pti.api.transit.application.GetRoute;
import dev.pti.api.transit.application.ListRoutes;
import dev.pti.api.transit.domain.RouteCatalog;
import dev.pti.api.transit.domain.RouteDetail;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** E-01 {@code GET /routes} and E-02 {@code GET /routes/{routeId}} (DOC-32 §3): the static side of the network. */
@RestController
class RouteController {

    private static final CacheControl LIST_CACHE =
            CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic();
    private static final CacheControl DETAIL_CACHE =
            CacheControl.maxAge(Duration.ofSeconds(300)).cachePublic();

    private final ListRoutes listRoutes;
    private final GetRoute getRoute;

    RouteController(ListRoutes listRoutes, GetRoute getRoute) {
        this.listRoutes = listRoutes;
        this.getRoute = getRoute;
    }

    @GetMapping(ApiPaths.V1 + "/routes")
    @Operation(
            operationId = "listRoutes",
            summary = "The routes of the active GTFS feed, for the route picker, map colours and the OTP ranking")
    @ApiResponse(
            responseCode = "200",
            description = "Every route of the active feed, optionally only of the given route types",
            content = @Content(examples = @ExampleObject(name = "routes", value = """
                            {"feedVersionId": 3, "items": [{"routeId": "18", "shortName": "18", "longName": "Nicollet Av - Nicollet Mall - 1st Av", "displayName": "18", "routeType": 3, "color": "0053A0", "textColor": "FFFFFF", "sortOrder": 18, "typicalHeadwaySeconds": 600}]}
                            """)))
    ResponseEntity<RoutesResponse> listRoutes(@RequestParam(required = false) List<Integer> routeType) {
        TransitParams.checkCount("routeType", routeType == null ? 0 : routeType.size());
        Set<Integer> types = routeType == null ? Set.of() : new HashSet<>(routeType);
        WithAsOf<RouteCatalog> result = listRoutes.execute(types);
        return ResponseEntity.ok()
                .cacheControl(LIST_CACHE)
                .headers(DataAsOfHeader.of(result.asOf()))
                .body(RoutesResponse.from(result.value()));
    }

    @GetMapping(ApiPaths.V1 + "/routes/{routeId}")
    @Operation(operationId = "getRoute", summary = "A route with the line and the ordered stops of each direction")
    @ApiResponse(
            responseCode = "200",
            description = "The route and its directions",
            content = @Content(examples = @ExampleObject(name = "route", value = """
                            {"routeId": "18", "feedVersionId": 3, "shortName": "18", "longName": "Nicollet Av - Nicollet Mall - 1st Av", "displayName": "18", "routeType": 3, "color": "0053A0", "textColor": "FFFFFF", "typicalHeadwaySeconds": 600, "directions": [{"directionId": 0, "label": "NB", "headsign": "Downtown Minneapolis", "tripCount": 312, "shapeId": "180077", "geometrySource": "SHAPE", "geometry": {"type": "LineString", "coordinates": [[-93.278123, 44.923411], [-93.27809, 44.925002]]}, "stops": [{"stopId": "51405", "code": "51405", "name": "Nicollet Ave & 46th St", "lat": 44.920401, "lon": -93.278012, "stopSequence": 1}]}]}
                            """)))
    @ApiResponse(responseCode = "404", description = "The route is not in the active feed")
    ResponseEntity<RouteDetailResponse> getRoute(@PathVariable String routeId) {
        WithAsOf<RouteDetail> result = getRoute.execute(routeId);
        return ResponseEntity.ok()
                .cacheControl(DETAIL_CACHE)
                .headers(DataAsOfHeader.of(result.asOf()))
                .body(RouteDetailResponse.from(result.value()));
    }
}
