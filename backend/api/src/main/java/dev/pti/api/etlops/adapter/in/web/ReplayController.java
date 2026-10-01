package dev.pti.api.etlops.adapter.in.web;

import dev.pti.api.etlops.adapter.in.web.ReplayResponses.ReplayEstimateResponse;
import dev.pti.api.etlops.adapter.in.web.ReplayResponses.ReplayResponse;
import dev.pti.api.etlops.application.EstimateRawReplay;
import dev.pti.api.etlops.application.GetReplay;
import dev.pti.api.etlops.application.ListReplays;
import dev.pti.api.etlops.application.RequestRawReplay;
import dev.pti.api.etlops.application.Submitted;
import dev.pti.api.etlops.domain.IdempotencyKey;
import dev.pti.api.etlops.domain.ReplayFilter;
import dev.pti.api.etlops.domain.ReplayKind;
import dev.pti.api.etlops.domain.ReplayRequest;
import dev.pti.api.platform.adapter.in.web.ApiPaths;
import dev.pti.api.platform.adapter.in.web.CursorCodec;
import dev.pti.api.platform.adapter.in.web.PageParams;
import dev.pti.api.platform.adapter.in.web.PagedResponse;
import dev.pti.api.platform.adapter.in.web.RequestValues;
import dev.pti.api.platform.adapter.in.web.TimeRanges;
import dev.pti.api.platform.domain.ApiException.FieldError;
import dev.pti.api.platform.domain.Caller;
import dev.pti.api.platform.domain.PageRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * E-50 {@code POST /etl/replays}, E-51 {@code GET /etl/replays}, E-52 {@code GET /etl/replays/{id}} and E-53 {@code GET
 * /etl/replays/estimate} (DOC-32 §8): replaying a range of the raw zone. The API only writes the request; {@code
 * etl-batch} runs it (ADR-0013). {@code fromTs} and {@code toTs} are Kafka record times, real time, and must carry an
 * offset.
 */
@RestController
class ReplayController {

    private static final Duration LIST_SPAN = Duration.ofDays(7);
    private static final List<String> STATUSES = List.of("PENDING", "RUNNING", "DONE", "FAILED");
    private static final List<String> SOURCES = List.of(
            "GTFS_RT_VEHICLE_POSITION",
            "GTFS_RT_TRIP_UPDATE",
            "TICKETING_SALES",
            "TICKETING_SALE_POINTS",
            "GTFS_STATIC");

    private static final String ESTIMATE_PATH = "/etl/replays/estimate";

    /** The body of E-50. */
    record RawReplayBody(
            @NotBlank String source,
            @NotBlank String fromTs,
            @NotBlank String toTs,
            @Nullable Boolean recomputeAnalytics) {}

    private final RequestRawReplay requestRawReplay;
    private final ListReplays listReplays;
    private final GetReplay getReplay;
    private final EstimateRawReplay estimateRawReplay;
    private final PageParams paging;
    private final TimeRanges ranges;

    ReplayController(
            RequestRawReplay requestRawReplay,
            ListReplays listReplays,
            GetReplay getReplay,
            EstimateRawReplay estimateRawReplay,
            PageParams paging,
            @Qualifier("auditTimeRanges") TimeRanges ranges) {
        this.requestRawReplay = requestRawReplay;
        this.listReplays = listReplays;
        this.getReplay = getReplay;
        this.estimateRawReplay = estimateRawReplay;
        this.paging = paging;
        this.ranges = ranges;
    }

    @PostMapping(ApiPaths.V1 + "/etl/replays")
    @Operation(operationId = "requestRawReplay", summary = "Replay a range of the raw zone (operator, Idempotency-Key)")
    @ApiResponse(
            responseCode = "202",
            description = "The replay request is stored; etl-batch runs it. Location is the replay",
            content = @Content(examples = @ExampleObject(name = "replay", value = """
                            {"id": "0192f5a0-1b2c-7d3e-8f4a-5b6c7d8e9f0a", "kind": "RAW_RANGE", "source": "GTFS_RT_TRIP_UPDATE", "fromTs": "2026-09-28T00:00:00Z", "toTs": "2026-09-29T00:00:00Z", "recomputeAnalytics": true, "status": "PENDING", "requestedBy": "user:operator", "requestedAt": "2026-09-29T20:39:58Z"}
                            """)))
    @ApiResponse(
            responseCode = "409",
            description =
                    "replay-already-running: the source has a replay waiting or running, existingReplayId says which")
    @ApiResponse(
            responseCode = "422",
            description = "unsupported-source, replay-window-invalid or idempotency-key-reused")
    ResponseEntity<ReplayResponse> requestRawReplay(
            Caller caller,
            @RequestHeader(value = IdempotencyKey.HEADER, required = false) @Nullable String idempotencyKey,
            @Valid @RequestBody RawReplayBody body) {
        String key = IdempotencyKey.check(idempotencyKey);
        List<FieldError> errors = new java.util.ArrayList<>();
        Instant from = time("fromTs", body.fromTs(), errors);
        Instant to = time("toTs", body.toTs(), errors);
        RequestValues.throwIfAny(errors);
        Submitted<ReplayRequest> submitted = requestRawReplay.execute(
                caller, body.source(), from, to, Boolean.TRUE.equals(body.recomputeAnalytics()), key);
        ReplayRequest request = submitted.value();
        return ResponseEntity.accepted()
                .location(URI.create(ApiPaths.V1 + "/etl/replays/" + request.id()))
                .body(ReplayResponse.from(request, false));
    }

    @GetMapping(ApiPaths.V1 + "/etl/replays")
    @Operation(operationId = "listReplays", summary = "Replays of a window, newest first (viewer)")
    @ApiResponse(
            responseCode = "200",
            description = "A page of replays requested in the window (default 7 days, at most 31)",
            content = @Content(examples = @ExampleObject(name = "replays", value = """
                            {"items": [{"id": "0192f5a0-1b2c-7d3e-8f4a-5b6c7d8e9f0a", "kind": "RAW_RANGE", "source": "GTFS_RT_TRIP_UPDATE", "fromTs": "2026-09-28T00:00:00Z", "toTs": "2026-09-29T00:00:00Z", "recomputeAnalytics": true, "status": "RUNNING", "requestedBy": "user:operator", "requestedAt": "2026-09-29T20:39:58Z", "startedAt": "2026-09-29T20:40:02Z", "jobExecutionId": 4127, "runId": "job:4127"}]}
                            """)))
    PagedResponse<ReplayResponse> listReplays(
            @RequestParam(required = false) @Nullable String kind,
            @RequestParam(required = false) @Nullable List<String> status,
            @RequestParam(required = false) @Nullable String source,
            @RequestParam(required = false) @Nullable String requestedBy,
            @RequestParam(required = false) @Nullable String from,
            @RequestParam(required = false) @Nullable String to,
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String cursor) {
        String fingerprint = CursorCodec.fingerprint(RequestValues.filters(
                "kind", kind, "status", status, "source", source, "requestedBy", requestedBy, "from", from, "to", to));
        PageRequest page = paging.resolve(limit, cursor, fingerprint);
        TimeRanges.Range range = ranges.resolveThroughNow(from, to, LIST_SPAN);
        String chosenKind = RequestValues.oneOf(
                "kind",
                kind,
                Arrays.stream(ReplayKind.values()).map(ReplayKind::name).toList());
        ReplayFilter filter = new ReplayFilter(
                range.from(),
                range.to(),
                chosenKind != null ? ReplayKind.valueOf(chosenKind) : null,
                RequestValues.allOf("status", status, STATUSES),
                RequestValues.oneOf("source", source, SOURCES),
                requestedBy != null && !requestedBy.isBlank() ? requestedBy : null);
        return paging.respond(
                listReplays.execute(filter, page), request -> ReplayResponse.from(request, false), fingerprint);
    }

    @GetMapping(ApiPaths.V1 + ESTIMATE_PATH)
    @Operation(
            operationId = "estimateRawReplay",
            summary = "Estimate how many messages and how long a raw zone replay will take (operator)")
    @ApiResponse(
            responseCode = "200",
            description =
                    "The estimate from the micro-batch log; without history there are no numbers and basis is UNAVAILABLE",
            content = @Content(examples = @ExampleObject(name = "estimate", value = """
                            {"source": "GTFS_RT_TRIP_UPDATE", "fromTs": "2026-09-28T00:00:00Z", "toTs": "2026-09-29T00:00:00Z", "estimatedMessages": 1152000, "estimatedDurationSeconds": 384, "basis": "STREAM_BATCH_LOG", "coverage": 1.0, "warnings": []}
                            """)))
    ReplayEstimateResponse estimateRawReplay(
            @RequestParam String source, @RequestParam String fromTs, @RequestParam String toTs) {
        List<FieldError> errors = new java.util.ArrayList<>();
        Instant from = time("fromTs", fromTs, errors);
        Instant to = time("toTs", toTs, errors);
        RequestValues.throwIfAny(errors);
        return ReplayEstimateResponse.from(estimateRawReplay.execute(source, from, to));
    }

    @GetMapping(ApiPaths.V1 + "/etl/replays/{id}")
    @Operation(operationId = "getReplay", summary = "One replay, with the progress of its step while it runs (viewer)")
    @ApiResponse(
            responseCode = "200",
            description = "The replay; progress only while RUNNING, stats once it has ended",
            content = @Content(examples = @ExampleObject(name = "running", value = """
                            {"id": "0192f5a0-1b2c-7d3e-8f4a-5b6c7d8e9f0a", "kind": "RAW_RANGE", "source": "GTFS_RT_TRIP_UPDATE", "fromTs": "2026-09-28T00:00:00Z", "toTs": "2026-09-29T00:00:00Z", "recomputeAnalytics": true, "status": "RUNNING", "requestedBy": "user:operator", "requestedAt": "2026-09-29T20:39:58Z", "startedAt": "2026-09-29T20:40:02Z", "jobExecutionId": 4127, "runId": "job:4127", "progress": {"step": "replayRecords", "readCount": 412000, "writeCount": 411050, "skipCount": 950, "updatedAt": "2026-09-29T20:47:11Z"}}
                            """)))
    @ApiResponse(responseCode = "404", description = "No such replay")
    ReplayResponse getReplay(@PathVariable String id) {
        return ReplayResponse.from(getReplay.execute(RequestValues.uuidOrNotFound(id, "The replay")));
    }

    private static @Nullable Instant time(String field, String text, List<FieldError> errors) {
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            errors.add(new FieldError(field, "must be an ISO-8601 time with an offset, such as 2026-09-29T00:00:00Z"));
            return null;
        }
    }
}
