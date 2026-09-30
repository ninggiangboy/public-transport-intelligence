package dev.pti.api.transit.adapter.in.web;

import dev.pti.api.platform.adapter.in.web.ApiPaths;
import dev.pti.api.platform.adapter.in.web.DataAsOfHeader;
import dev.pti.api.platform.domain.Caller;
import dev.pti.api.platform.domain.WithAsOf;
import dev.pti.api.transit.application.ListLiveVehicles;
import dev.pti.api.transit.domain.LiveVehicles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * E-05 {@code GET /vehicles/live} (DOC-32 §3): the snapshot of vehicle positions. The body differs by role (the
 * bunching overlay is for viewers), so the response varies by {@code Authorization}.
 */
@RestController
class VehicleController {

    private final ListLiveVehicles listLiveVehicles;

    VehicleController(ListLiveVehicles listLiveVehicles) {
        this.listLiveVehicles = listLiveVehicles;
    }

    @GetMapping(ApiPaths.V1 + "/vehicles/live")
    @Operation(
            operationId = "listLiveVehicles",
            summary = "The newest position of every vehicle that reported recently; viewers also see bunching")
    @ApiResponse(
            responseCode = "200",
            description = "A snapshot ordered by vehicle id, at most 1,500 vehicles",
            content = @Content(examples = @ExampleObject(name = "vehicles", value = """
                            {"businessNow": "2026-09-29T21:19:35Z", "count": 1, "items": [{"vehicleId": "1203", "label": "1203", "routeId": "18", "tripId": "27371245-AUG26-MVS-BUS-Weekday-01", "directionId": 0, "headsign": "Downtown Minneapolis", "lat": 44.948121, "lon": -93.278004, "bearing": 358.0, "speedMps": 7.4, "currentStatus": "IN_TRANSIT_TO", "stopId": "51420", "currentStopSequence": 14, "occupancyStatus": "MANY_SEATS_AVAILABLE", "eventTimestamp": "2026-09-29T21:19:30Z", "delaySeconds": 95, "stopArrivalAt": "2026-09-29T21:20:41Z", "bunching": {"episodeId": "6f1c2a9e-4b1d-5c8e-9a2f-3d4e5f6a7b8c", "role": "FOLLOWER", "partnerVehicleId": "1187", "gapSeconds": 112, "headwaySeconds": 600}}]}
                            """)))
    ResponseEntity<LiveVehiclesResponse> listLiveVehicles(
            Caller caller, @RequestParam(required = false) List<String> routeId) {
        Set<String> routeIds = new LinkedHashSet<>(TransitParams.values("routeId", routeId));
        WithAsOf<LiveVehicles> result = listLiveVehicles.execute(caller, routeIds);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .varyBy(HttpHeaders.AUTHORIZATION)
                .headers(DataAsOfHeader.of(result.asOf()))
                .body(LiveVehiclesResponse.from(result.value()));
    }
}
