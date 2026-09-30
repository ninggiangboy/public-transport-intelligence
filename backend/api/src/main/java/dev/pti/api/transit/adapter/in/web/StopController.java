package dev.pti.api.transit.adapter.in.web;

import dev.pti.api.platform.adapter.in.web.ApiPaths;
import dev.pti.api.platform.adapter.in.web.CursorCodec;
import dev.pti.api.platform.adapter.in.web.DataAsOfHeader;
import dev.pti.api.platform.adapter.in.web.PageParams;
import dev.pti.api.platform.adapter.in.web.PagedResponse;
import dev.pti.api.platform.domain.ApiException.FieldError;
import dev.pti.api.platform.domain.Caller;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;
import dev.pti.api.platform.domain.ValidationException;
import dev.pti.api.platform.domain.WithAsOf;
import dev.pti.api.transit.application.GetStop;
import dev.pti.api.transit.application.SearchStops;
import dev.pti.api.transit.application.StopSearch;
import dev.pti.api.transit.domain.BoundingBox;
import dev.pti.api.transit.domain.StopDetail;
import dev.pti.api.transit.domain.StopMatch;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** E-06 {@code GET /stops} and E-07 {@code GET /stops/{stopId}} (DOC-32 §3). */
@RestController
class StopController {

    private static final int TEXT_DEFAULT_LIMIT = 20;
    private static final int TEXT_MAX_LIMIT = 50;
    private static final int AREA_DEFAULT_LIMIT = 200;
    private static final int MIN_QUERY_LENGTH = 2;
    private static final int MAX_QUERY_LENGTH = 100;
    private static final CacheControl SEARCH_CACHE =
            CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic();

    private final SearchStops searchStops;
    private final GetStop getStop;
    private final PageParams paging;

    StopController(SearchStops searchStops, GetStop getStop, PageParams paging) {
        this.searchStops = searchStops;
        this.getStop = getStop;
        this.paging = paging;
    }

    @GetMapping(ApiPaths.V1 + "/stops")
    @Operation(
            operationId = "searchStops",
            summary = "Stops by name or code (q), or the stops in a map window (bbox) or of a route (routeId)")
    @ApiResponse(
            responseCode = "200",
            description = "Ranked stops for q, which has no paging; keyset-paged stops for bbox and routeId",
            content = @Content(examples = @ExampleObject(name = "stops", value = """
                            {"items": [{"stopId": "51405", "code": "51405", "name": "Nicollet Ave & 46th St", "lat": 44.920401, "lon": -93.278012, "locationType": 0, "wheelchairBoarding": 1, "routeIds": ["18"]}], "nextCursor": "eyJ2IjoxLCJrIjpbIjUxNDA1Il0sImYiOiI3YzFhIn0"}
                            """)))
    ResponseEntity<PagedResponse<StopResponses.StopItemResponse>> searchStops(
            @RequestParam(required = false) @Nullable String q,
            @RequestParam(required = false) @Nullable String bbox,
            @RequestParam(required = false) @Nullable String routeId,
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String cursor) {
        boolean byText = q != null;
        boolean byArea = bbox != null || routeId != null;
        if (byText == byArea) {
            throw ValidationException.of("q", "exactly one of q, or bbox and/or routeId, is needed");
        }
        return byText ? searchByText(q, limit, cursor) : searchByArea(bbox, routeId, limit, cursor);
    }

    private ResponseEntity<PagedResponse<StopResponses.StopItemResponse>> searchByText(
            String q, @Nullable Integer limit, @Nullable String cursor) {
        String text = q.trim();
        int effective = limit != null ? limit : TEXT_DEFAULT_LIMIT;
        List<FieldError> errors = new ArrayList<>();
        if (text.length() < MIN_QUERY_LENGTH || text.length() > MAX_QUERY_LENGTH) {
            errors.add(new FieldError(
                    "q", "must be " + MIN_QUERY_LENGTH + " to " + MAX_QUERY_LENGTH + " characters after trimming"));
        }
        TransitParams.checkRange("limit", effective, 1, TEXT_MAX_LIMIT, errors);
        if (cursor != null) {
            errors.add(new FieldError("cursor", "is only accepted with bbox or routeId"));
        }
        TransitParams.throwIfAny(errors);
        return respond(searchStops.execute(new StopSearch.ByText(text, effective)), "");
    }

    private ResponseEntity<PagedResponse<StopResponses.StopItemResponse>> searchByArea(
            @Nullable String bbox, @Nullable String routeId, @Nullable Integer limit, @Nullable String cursor) {
        if (routeId != null && routeId.isBlank()) {
            throw ValidationException.of("routeId", "must not be blank");
        }
        BoundingBox box = bbox != null ? TransitParams.bbox(bbox) : null;
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("bbox", bbox);
        filters.put("routeId", routeId);
        String fingerprint = CursorCodec.fingerprint(filters);
        PageRequest page = paging.resolve(limit != null ? limit : AREA_DEFAULT_LIMIT, cursor, fingerprint);
        return respond(searchStops.execute(new StopSearch.ByArea(box, routeId, page)), fingerprint);
    }

    private ResponseEntity<PagedResponse<StopResponses.StopItemResponse>> respond(
            WithAsOf<Page<StopMatch>> result, String fingerprint) {
        return ResponseEntity.ok()
                .cacheControl(SEARCH_CACHE)
                .headers(DataAsOfHeader.of(result.asOf()))
                .body(paging.respond(result.value(), StopResponses.StopItemResponse::from, fingerprint));
    }

    @GetMapping(ApiPaths.V1 + "/stops/{stopId}")
    @Operation(operationId = "getStop", summary = "A stop, the routes that call at it and the disruptions open on them")
    @ApiResponse(
            responseCode = "200",
            description = "The stop; activeDisruptions holds what the caller may see",
            content = @Content(examples = @ExampleObject(name = "stop", value = """
                            {"stopId": "51405", "code": "51405", "name": "Nicollet Ave & 46th St", "lat": 44.920401, "lon": -93.278012, "locationType": 0, "wheelchairBoarding": 1, "routes": [{"routeId": "18", "displayName": "18", "color": "0053A0", "textColor": "FFFFFF", "headsigns": ["Downtown Minneapolis", "Nicollet & 66th St"]}], "activeDisruptions": [{"alertId": "0a4c7e1f-2b3d-5e6f-8a9b-1c2d3e4f5a6b", "disruptionId": "9d8e7f6a-5b4c-5d3e-8f2a-1b0c9d8e7f6a", "routeId": "18", "directionId": 0, "severity": 1, "title": "Delays on route 18 northbound", "startedAt": "2026-09-29T20:58:00Z"}]}
                            """)))
    @ApiResponse(responseCode = "404", description = "The stop is not in the active feed")
    ResponseEntity<StopResponses.StopDetailResponse> getStop(Caller caller, @PathVariable String stopId) {
        WithAsOf<StopDetail> result = getStop.execute(caller, stopId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .varyBy(HttpHeaders.AUTHORIZATION)
                .headers(DataAsOfHeader.of(result.asOf()))
                .body(StopResponses.StopDetailResponse.from(result.value()));
    }
}
