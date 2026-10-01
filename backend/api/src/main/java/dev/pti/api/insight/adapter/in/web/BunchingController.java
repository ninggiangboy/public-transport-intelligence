package dev.pti.api.insight.adapter.in.web;

import dev.pti.api.insight.application.BunchingQuery;
import dev.pti.api.insight.application.GetBunchingEpisode;
import dev.pti.api.insight.application.ListBunchingEpisodes;
import dev.pti.api.insight.config.DispatchProperties;
import dev.pti.api.insight.domain.BunchingDetail;
import dev.pti.api.insight.domain.BunchingEpisode;
import dev.pti.api.platform.adapter.in.web.ApiPaths;
import dev.pti.api.platform.adapter.in.web.CursorCodec;
import dev.pti.api.platform.adapter.in.web.DataAsOfHeader;
import dev.pti.api.platform.adapter.in.web.PageParams;
import dev.pti.api.platform.adapter.in.web.PagedResponse;
import dev.pti.api.platform.adapter.in.web.RequestValues;
import dev.pti.api.platform.adapter.in.web.TimeRanges;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;
import dev.pti.api.platform.domain.WithAsOf;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** E-10 {@code GET /insights/bunching} and E-11 {@code GET /insights/bunching/{id}} (DOC-32 §4), for viewers. */
@RestController
class BunchingController {

    private static final Duration DEFAULT_SPAN = Duration.ofHours(24);
    private static final List<String> STATUSES = List.of("OPEN", "CLOSED");

    private final ListBunchingEpisodes listEpisodes;
    private final GetBunchingEpisode getEpisode;
    private final TimeRanges timeRanges;
    private final PageParams paging;
    private final DispatchProperties dispatch;
    private final JsonMapper json;

    BunchingController(
            ListBunchingEpisodes listEpisodes,
            GetBunchingEpisode getEpisode,
            @Qualifier("eventTimeRanges") TimeRanges timeRanges,
            PageParams paging,
            DispatchProperties dispatch,
            JsonMapper json) {
        this.listEpisodes = listEpisodes;
        this.getEpisode = getEpisode;
        this.timeRanges = timeRanges;
        this.paging = paging;
        this.dispatch = dispatch;
        this.json = json;
    }

    @GetMapping(ApiPaths.V1 + "/insights/bunching")
    @Operation(
            operationId = "listBunchingEpisodes",
            summary = "Bus bunching episodes that intersect a time range, newest first (viewer)")
    @ApiResponse(
            responseCode = "200",
            description = "One page of episodes; closed ones also have episodeEnd and closeReason",
            content = @Content(examples = @ExampleObject(name = "open episode", value = """
                            {"items": [{"id": "6f1c2a9e-4b1d-5c8e-9a2f-3d4e5f6a7b8c", "routeId": "18", "directionId": 0, "vehicleLeader": "1187", "vehicleFollower": "1203", "episodeStart": "2026-09-29T21:12:15Z", "status": "OPEN", "scheduledHeadwaySeconds": 600, "thresholdSeconds": 300, "minGapSeconds": 96, "lastGapSeconds": 112, "openStopId": "51418", "evaluationCount": 29, "lastEvaluatedAt": "2026-09-29T21:19:30Z", "suggestion": {"id": "3b2a1c0d-9e8f-4a7b-8c6d-5e4f3a2b1c0d", "action": "hold_follower", "actionConfidence": 0.82}}], "nextCursor": "eyJ2IjoxLCJrIjpbIjIwMjYtMDktMjlUMjE6MTI6MTVaIiwiNmYxYzJhOWUtNGIxZC01YzhlLTlhMmYtM2Q0ZTVmNmE3YjhjIl0sImYiOiI5ZjJjIn0"}
                            """)))
    @ApiResponse(responseCode = "400", description = "A parameter is not valid")
    ResponseEntity<PagedResponse<BunchingResponse>> listBunchingEpisodes(
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
        WithAsOf<Page<BunchingEpisode>> result =
                listEpisodes.execute(new BunchingQuery(range.from(), range.to(), routeIds, statusFilter), request);
        return ResponseEntity.ok()
                .headers(DataAsOfHeader.of(result.asOf()))
                .body(paging.respond(result.value(), BunchingResponse::from, fingerprint));
    }

    @GetMapping(ApiPaths.V1 + "/insights/bunching/{id}")
    @Operation(
            operationId = "getBunchingEpisode",
            summary = "One bunching episode with its trips, batch and whole dispatch suggestion (viewer)")
    @ApiResponse(
            responseCode = "200",
            description = "The episode",
            content = @Content(examples = @ExampleObject(name = "closed episode", value = """
                            {"id": "6f1c2a9e-4b1d-5c8e-9a2f-3d4e5f6a7b8c", "routeId": "18", "directionId": 0, "vehicleLeader": "1187", "vehicleFollower": "1203", "tripLeader": "t-2041", "tripFollower": "t-2043", "episodeStart": "2026-09-29T21:12:15Z", "episodeEnd": "2026-09-29T21:31:45Z", "status": "CLOSED", "closeReason": "GAP_RECOVERED", "scheduledHeadwaySeconds": 600, "thresholdSeconds": 300, "minGapSeconds": 96, "lastGapSeconds": 341, "openStopId": "51418", "evaluationCount": 78, "lastEvaluatedAt": "2026-09-29T21:31:45Z", "batchId": "0b9d3c52-6a1f-4c1e-9f0a-2a7d1e4c8b10", "enrichmentStatus": "DONE", "suggestion": {"id": "3b2a1c0d-9e8f-4a7b-8c6d-5e4f3a2b1c0d", "bunchingId": "6f1c2a9e-4b1d-5c8e-9a2f-3d4e5f6a7b8c", "routeId": "18", "action": "hold_follower", "actionConfidence": 0.82, "lowConfidence": false, "modelVersion": "jev@0.2.0", "createdAt": "2026-09-29T21:12:47Z", "stateSnapshot": {"task": "Suggest one dispatch action for a pair of buses running too close together on the same route."}}}
                            """)))
    @ApiResponse(responseCode = "404", description = "There is no such episode")
    ResponseEntity<BunchingDetailResponse> getBunchingEpisode(@PathVariable String id) {
        WithAsOf<BunchingDetail> result = getEpisode.execute(RequestValues.uuidOrNotFound(id, "The bunching episode"));
        return ResponseEntity.ok()
                .headers(DataAsOfHeader.of(result.asOf()))
                .body(BunchingDetailResponse.from(result.value(), dispatch.lowConfidence(), json));
    }
}
