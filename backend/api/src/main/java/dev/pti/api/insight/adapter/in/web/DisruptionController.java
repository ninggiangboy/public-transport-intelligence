package dev.pti.api.insight.adapter.in.web;

import dev.pti.api.insight.application.DisruptionQuery;
import dev.pti.api.insight.application.GetDisruptionEpisode;
import dev.pti.api.insight.application.ListDisruptionEpisodes;
import dev.pti.api.insight.domain.DisruptionEpisode;
import dev.pti.api.platform.adapter.in.web.ApiPaths;
import dev.pti.api.platform.adapter.in.web.CursorCodec;
import dev.pti.api.platform.adapter.in.web.DataAsOfHeader;
import dev.pti.api.platform.adapter.in.web.PageParams;
import dev.pti.api.platform.adapter.in.web.PagedResponse;
import dev.pti.api.platform.adapter.in.web.RequestValues;
import dev.pti.api.platform.adapter.in.web.TimeRanges;
import dev.pti.api.platform.domain.Caller;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;
import dev.pti.api.platform.domain.WithAsOf;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * E-12 {@code GET /insights/disruption} and E-13 {@code GET /insights/disruption/{id}} (DOC-32 §4): open to everyone,
 * but an anonymous caller gets the public view of the episodes whose alert is still public, and a viewer the whole
 * record of all of them. The answer differs by caller, so it says {@code Vary: Authorization}; only the public answer
 * may be cached (5 s, in the use case's reader).
 */
@RestController
class DisruptionController {

    private static final Duration DEFAULT_SPAN = Duration.ofHours(24);
    private static final List<String> STATUSES = List.of("OPEN", "CLOSED");

    private final ListDisruptionEpisodes listEpisodes;
    private final GetDisruptionEpisode getEpisode;
    private final TimeRanges timeRanges;
    private final PageParams paging;

    DisruptionController(
            ListDisruptionEpisodes listEpisodes,
            GetDisruptionEpisode getEpisode,
            @Qualifier("eventTimeRanges") TimeRanges timeRanges,
            PageParams paging) {
        this.listEpisodes = listEpisodes;
        this.getEpisode = getEpisode;
        this.timeRanges = timeRanges;
        this.paging = paging;
    }

    @GetMapping(ApiPaths.V1 + "/insights/disruption")
    @Operation(
            operationId = "listDisruptionEpisodes",
            summary =
                    "Service disruption episodes that intersect a time range, newest first (public view for anonymous)")
    @ApiResponse(
            responseCode = "200",
            description = "One page of episodes; an anonymous caller gets the public view of the public ones only",
            content = @Content(examples = @ExampleObject(name = "viewer", value = """
                            {"items": [{"id": "9d8e7f6a-5b4c-5d3e-8f2a-1b0c9d8e7f6a", "routeId": "18", "directionId": 0, "episodeStart": "2026-09-29T20:58:00Z", "status": "OPEN", "severity": 1, "audience": "PUBLIC", "baselineMeanSeconds": 61.4, "baselineStddevSeconds": 38.0, "currentAvgDelaySeconds": 212.7, "currentZScore": 3.98, "peakAvgDelaySeconds": 230.1, "peakZScore": 4.44, "sampleCount": 57, "affectedStopIds": ["51418", "51420", "51422"], "lastBucket": "2026-09-29T21:19:00Z", "enrichmentStatus": "DONE", "dataIssueProbability": 0.12, "likelyCause": "traffic", "causeConfidence": 0.71, "modelVersion": "jev@0.2.0"}], "nextCursor": "eyJ2IjoxLCJrIjpbIjIwMjYtMDktMjlUMjA6NTg6MDBaIiwiOWQ4ZTdmNmEtNWI0Yy01ZDNlLThmMmEtMWIwYzlkOGU3ZjZhIl0sImYiOiI5ZjJjIn0"}
                            """)))
    @ApiResponse(responseCode = "400", description = "A parameter is not valid")
    ResponseEntity<PagedResponse<DisruptionResponse>> listDisruptionEpisodes(
            Caller caller,
            @RequestParam(required = false) @Nullable String from,
            @RequestParam(required = false) @Nullable String to,
            @RequestParam(required = false) @Nullable List<String> routeId,
            @RequestParam(required = false) @Nullable String status,
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String cursor) {
        List<String> routeIds = RequestValues.distinct("routeId", routeId);
        String statusFilter = RequestValues.oneOf("status", status, STATUSES);
        TimeRanges.Range range = timeRanges.resolveThroughNow(from, to, DEFAULT_SPAN);
        String fingerprint = CursorCodec.fingerprint(
                RequestValues.filters("from", from, "to", to, "routeId", routeIds, "status", statusFilter));
        PageRequest request = paging.resolve(limit, cursor, fingerprint);
        WithAsOf<Page<DisruptionEpisode>> result = listEpisodes.execute(
                caller, new DisruptionQuery(range.from(), range.to(), routeIds, statusFilter, false), request);
        return ResponseEntity.ok()
                .headers(headers(caller, result.asOf()))
                .body(paging.respond(result.value(), view(caller), fingerprint));
    }

    @GetMapping(ApiPaths.V1 + "/insights/disruption/{id}")
    @Operation(
            operationId = "getDisruptionEpisode",
            summary = "One disruption episode (public view for anonymous, which gets a 404 for a non-public one)")
    @ApiResponse(
            responseCode = "200",
            description = "The episode; a viewer also gets closeReason, batchId and enrichedAt",
            content = @Content(examples = @ExampleObject(name = "anonymous", value = """
                            {"id": "9d8e7f6a-5b4c-5d3e-8f2a-1b0c9d8e7f6a", "routeId": "18", "directionId": 0, "episodeStart": "2026-09-29T20:58:00Z", "status": "OPEN", "severity": 1, "currentAvgDelaySeconds": 212.7, "peakAvgDelaySeconds": 230.1, "affectedStopIds": ["51418", "51420", "51422"]}
                            """)))
    @ApiResponse(responseCode = "404", description = "There is no such episode, or the caller may not see it")
    ResponseEntity<DisruptionResponse> getDisruptionEpisode(Caller caller, @PathVariable String id) {
        WithAsOf<DisruptionEpisode> result =
                getEpisode.execute(caller, RequestValues.uuidOrNotFound(id, "The disruption episode"));
        return ResponseEntity.ok()
                .headers(headers(caller, result.asOf()))
                .body(view(caller).apply(result.value()));
    }

    private static Function<DisruptionEpisode, DisruptionResponse> view(Caller caller) {
        return caller.isViewer() ? DisruptionResponse::fullView : DisruptionResponse::publicView;
    }

    /** {@code Vary: Authorization}, {@code X-Data-As-Of}, and {@code no-cache} for the public answer (DOC-31 §10.3). */
    private static HttpHeaders headers(Caller caller, @Nullable Instant asOf) {
        HttpHeaders headers = DataAsOfHeader.of(asOf);
        headers.set(HttpHeaders.VARY, HttpHeaders.AUTHORIZATION);
        if (!caller.isViewer()) {
            headers.setCacheControl(CacheControl.noCache());
        }
        return headers;
    }
}
