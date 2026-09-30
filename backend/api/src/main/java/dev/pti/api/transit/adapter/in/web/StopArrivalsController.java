package dev.pti.api.transit.adapter.in.web;

import dev.pti.api.platform.adapter.in.web.ApiPaths;
import dev.pti.api.platform.adapter.in.web.DataAsOfHeader;
import dev.pti.api.platform.domain.WithAsOf;
import dev.pti.api.transit.application.ListStopArrivals;
import dev.pti.api.transit.domain.StopArrivals;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** E-08 {@code GET /stops/{stopId}/arrivals} (DOC-32 §3): the next calls at a stop with predicted times. */
@RestController
class StopArrivalsController {

    private final ListStopArrivals listStopArrivals;

    StopArrivalsController(ListStopArrivals listStopArrivals) {
        this.listStopArrivals = listStopArrivals;
    }

    @GetMapping(ApiPaths.V1 + "/stops/{stopId}/arrivals")
    @Operation(
            operationId = "listStopArrivals",
            summary = "The next calls at a stop with a predicted arrival time and a confidence")
    @ApiResponse(
            responseCode = "200",
            description = "Upcoming calls ordered by predicted time; empty when there are none",
            content = @Content(examples = @ExampleObject(name = "arrivals", value = """
                            {"stopId": "51405", "businessNow": "2026-09-29T21:19:35Z", "realtimeEnabled": false, "items": [{"tripId": "27371245-AUG26-MVS-BUS-Weekday-01", "routeId": "18", "directionId": 0, "headsign": "Downtown Minneapolis", "serviceDate": "2026-09-29", "scheduledArrival": "2026-09-29T21:24:00Z", "predictedArrival": "2026-09-29T21:25:04Z", "predictedDelaySeconds": 64, "sampleCount": 36, "confidence": "HIGH"}]}
                            """)))
    @ApiResponse(responseCode = "404", description = "The stop is not in the active feed")
    ResponseEntity<StopArrivalsResponse> listStopArrivals(
            @PathVariable String stopId,
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String horizon) {
        Duration window = horizon != null ? TransitParams.duration("horizon", horizon) : null;
        WithAsOf<StopArrivals> result = listStopArrivals.execute(stopId, limit, window);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .headers(DataAsOfHeader.of(result.asOf()))
                .body(StopArrivalsResponse.from(result.value()));
    }
}
