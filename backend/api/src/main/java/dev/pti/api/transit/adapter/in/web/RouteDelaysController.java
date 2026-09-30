package dev.pti.api.transit.adapter.in.web;

import dev.pti.api.platform.adapter.in.web.ApiPaths;
import dev.pti.api.platform.adapter.in.web.DataAsOfHeader;
import dev.pti.api.platform.domain.ApiException.FieldError;
import dev.pti.api.platform.domain.WithAsOf;
import dev.pti.api.transit.application.GetRouteDelayProfile;
import dev.pti.api.transit.application.GetRouteDelays;
import dev.pti.api.transit.application.RouteDelaysQuery;
import dev.pti.api.transit.domain.BucketSize;
import dev.pti.api.transit.domain.DelayProfile;
import dev.pti.api.transit.domain.RouteDelays;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * E-03 {@code GET /routes/{routeId}/delays} and E-04 {@code GET /routes/{routeId}/delay-profile} (DOC-32 §3): how late
 * a route runs, over time and by stop.
 */
@RestController
class RouteDelaysController {

    private static final Duration DEFAULT_SPAN = Duration.ofDays(7);
    private static final CacheControl DELAYS_CACHE =
            CacheControl.maxAge(Duration.ofSeconds(60)).cachePrivate();
    private static final CacheControl PROFILE_CACHE =
            CacheControl.maxAge(Duration.ofSeconds(300)).cachePublic();

    private final GetRouteDelays getRouteDelays;
    private final GetRouteDelayProfile getRouteDelayProfile;
    private final TimeRanges timeRanges;

    RouteDelaysController(
            GetRouteDelays getRouteDelays, GetRouteDelayProfile getRouteDelayProfile, TimeRanges timeRanges) {
        this.getRouteDelays = getRouteDelays;
        this.getRouteDelayProfile = getRouteDelayProfile;
        this.timeRanges = timeRanges;
    }

    @GetMapping(ApiPaths.V1 + "/routes/{routeId}/delays")
    @Operation(
            operationId = "getRouteDelays",
            summary = "Observed delays of a route over time, by hour, day or hour of week (viewer)")
    @ApiResponse(
            responseCode = "200",
            description = "The delay buckets that have observations, in ascending order",
            content = @Content(examples = @ExampleObject(name = "hourly", value = """
                            {"routeId": "18", "bucket": "hour", "from": "2026-09-22T21:00:00Z", "to": "2026-09-29T21:00:00Z", "earlyToleranceSeconds": 300, "lateToleranceSeconds": 300, "items": [{"bucketStart": "2026-09-29T20:00:00Z", "avgDelaySeconds": 142.6, "medianDelaySeconds": 118, "p90DelaySeconds": 391, "observationCount": 1204, "onTimePercentage": 81.23}]}
                            """)))
    @ApiResponse(responseCode = "404", description = "The route is not in the active feed")
    ResponseEntity<RouteDelaysResponse> getRouteDelays(
            @PathVariable String routeId,
            @RequestParam(required = false) @Nullable String from,
            @RequestParam(required = false) @Nullable String to,
            @RequestParam(required = false) @Nullable String bucket,
            @RequestParam(required = false) @Nullable Integer directionId) {
        BucketSize size = TransitParams.bucket(bucket);
        List<FieldError> errors = new ArrayList<>();
        TransitParams.checkDirection(directionId, errors);
        TransitParams.throwIfAny(errors);
        TimeRanges.Range range = timeRanges.resolve(from, to, DEFAULT_SPAN);
        WithAsOf<RouteDelays> result =
                getRouteDelays.execute(new RouteDelaysQuery(routeId, range.from(), range.to(), size, directionId));
        return ResponseEntity.ok()
                .cacheControl(DELAYS_CACHE)
                .headers(DataAsOfHeader.of(result.asOf()))
                .body(RouteDelaysResponse.from(result.value()));
    }

    @GetMapping(ApiPaths.V1 + "/routes/{routeId}/delay-profile")
    @Operation(
            operationId = "getRouteDelayProfile",
            summary = "The historical delay at each stop of one direction for a weekday and hour")
    @ApiResponse(
            responseCode = "200",
            description = "The stops of the direction in order, with their historical delay and confidence",
            content = @Content(examples = @ExampleObject(name = "profile", value = """
                            {"routeId": "18", "directionId": 0, "dayOfWeek": 2, "hourOfDay": 16, "windowStart": "2026-09-01", "windowEnd": "2026-09-28", "computedAt": "2026-09-29T21:05:12Z", "items": [{"stopId": "51405", "name": "Nicollet Ave & 46th St", "stopSequence": 1, "avgDelaySeconds": 64.2, "medianDelaySeconds": 51, "p90DelaySeconds": 170, "sampleCount": 36, "confidence": "HIGH"}, {"stopId": "51406", "name": "Nicollet Ave & 44th St", "stopSequence": 2, "sampleCount": 0, "confidence": "NONE"}]}
                            """)))
    @ApiResponse(responseCode = "404", description = "The route or its direction does not exist")
    ResponseEntity<DelayProfileResponse> getRouteDelayProfile(
            @PathVariable String routeId,
            @RequestParam Integer directionId,
            @RequestParam(required = false) @Nullable Integer dayOfWeek,
            @RequestParam(required = false) @Nullable Integer hourOfDay) {
        List<FieldError> errors = new ArrayList<>();
        TransitParams.checkDirection(directionId, errors);
        TransitParams.checkRange("dayOfWeek", dayOfWeek, 1, 7, errors);
        TransitParams.checkRange("hourOfDay", hourOfDay, 0, 23, errors);
        TransitParams.throwIfAny(errors);
        WithAsOf<DelayProfile> result = getRouteDelayProfile.execute(routeId, directionId, dayOfWeek, hourOfDay);
        return ResponseEntity.ok()
                .cacheControl(PROFILE_CACHE)
                .headers(DataAsOfHeader.of(result.asOf()))
                .body(DelayProfileResponse.from(result.value()));
    }
}
