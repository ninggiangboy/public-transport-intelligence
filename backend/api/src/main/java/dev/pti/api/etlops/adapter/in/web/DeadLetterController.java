package dev.pti.api.etlops.adapter.in.web;

import dev.pti.api.etlops.adapter.in.web.DeadLetterResponses.ActionLogResponse;
import dev.pti.api.etlops.adapter.in.web.DeadLetterResponses.DeadLetterDetailResponse;
import dev.pti.api.etlops.adapter.in.web.DeadLetterResponses.DeadLetterItemResponse;
import dev.pti.api.etlops.adapter.in.web.DeadLetterResponses.DeadLetterSummaryResponse;
import dev.pti.api.etlops.application.GetDeadLetter;
import dev.pti.api.etlops.application.GetDeadLetterSummary;
import dev.pti.api.etlops.application.ListDeadLetterActions;
import dev.pti.api.etlops.application.ListDeadLetters;
import dev.pti.api.etlops.domain.ActionLogFilter;
import dev.pti.api.etlops.domain.DeadLetterFilter;
import dev.pti.api.etlops.domain.DeadLetterStatus;
import dev.pti.api.platform.adapter.in.web.ApiPaths;
import dev.pti.api.platform.adapter.in.web.CursorCodec;
import dev.pti.api.platform.adapter.in.web.PageParams;
import dev.pti.api.platform.adapter.in.web.PagedResponse;
import dev.pti.api.platform.adapter.in.web.RequestValues;
import dev.pti.api.platform.adapter.in.web.TimeRanges;
import dev.pti.api.platform.domain.Caller;
import dev.pti.api.platform.domain.PageRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * E-40 {@code GET /etl/dlq}, E-41 {@code GET /etl/dlq/summary}, E-42 {@code GET /etl/dlq/{id}} and E-48 {@code GET
 * /etl/dlq/actions} (DOC-32 §7): reading the dead letter queue and its action log, for a viewer. What changes a dead
 * letter is {@link DeadLetterCommandController}.
 */
@RestController
class DeadLetterController {

    private static final Duration ACTIONS_SPAN = Duration.ofHours(24);
    private static final List<String> STATUSES =
            Arrays.stream(DeadLetterStatus.values()).map(DeadLetterStatus::name).toList();
    private static final List<String> SOURCES = List.of(
            "GTFS_RT_VEHICLE_POSITION",
            "GTFS_RT_TRIP_UPDATE",
            "TICKETING_SALES",
            "TICKETING_SALE_POINTS",
            "GTFS_STATIC");
    private static final List<String> STAGES = List.of("DESERIALIZE", "SCHEMA", "BUSINESS", "DEDUP", "LOAD", "QUALITY");
    private static final List<String> CATEGORIES = List.of(
            "schema_violation",
            "referential_integrity",
            "upstream_api_error",
            "transient_network",
            "unknown",
            DeadLetterFilter.UNCLASSIFIED);
    private static final List<String> SEVERITIES = List.of("0", "1", "2", DeadLetterFilter.UNCLASSIFIED);
    private static final List<String> ACTIONS = List.of(
            "TRIAGED",
            "TRIAGE_FAILED",
            "AUTO_REPLAY_SCHEDULED",
            "CONFIRM_REQUESTED",
            "CONFIRMED",
            "MANUAL_REQUIRED",
            "EDITED",
            "REPLAY_REQUESTED",
            "REPLAYED",
            "REPLAY_FAILED",
            "DISCARDED",
            "RESOLVED");

    private final ListDeadLetters listDeadLetters;
    private final GetDeadLetterSummary getDeadLetterSummary;
    private final GetDeadLetter getDeadLetter;
    private final ListDeadLetterActions listDeadLetterActions;
    private final PageParams paging;
    private final TimeRanges ranges;

    DeadLetterController(
            ListDeadLetters listDeadLetters,
            GetDeadLetterSummary getDeadLetterSummary,
            GetDeadLetter getDeadLetter,
            ListDeadLetterActions listDeadLetterActions,
            PageParams paging,
            @Qualifier("auditTimeRanges") TimeRanges ranges) {
        this.listDeadLetters = listDeadLetters;
        this.getDeadLetterSummary = getDeadLetterSummary;
        this.getDeadLetter = getDeadLetter;
        this.listDeadLetterActions = listDeadLetterActions;
        this.paging = paging;
        this.ranges = ranges;
    }

    @GetMapping(ApiPaths.V1 + "/etl/dlq")
    @Operation(
            operationId = "listDeadLetters",
            summary = "Dead letters, newest first, with a preview of the payload (viewer)")
    @ApiResponse(
            responseCode = "200",
            description = "A page of dead letters. from and to are optional and have no default or maximum",
            content = @Content(examples = @ExampleObject(name = "deadLetters", value = """
                            {"items": [{"id": "0192f5b2-8d9e-7a0b-9c1d-2e3f4a5b6c7d", "source": "GTFS_RT_VEHICLE_POSITION", "stage": "SCHEMA", "ruleId": "DQ-01", "errorClass": "SchemaViolationException", "errorMessage": "$.payload.position.latitude: must be between -90 and 90", "status": "MANUAL", "category": "schema_violation", "categoryConfidence": 0.412, "severity": 2, "severityConfidence": 0.655, "businessKey": "1203|2026-09-29T21:18:45Z", "payloadPreview": "{\\"schema_version\\":1,\\"entity_type\\":\\"VEHICLE_POSITION\\"", "hasEditedPayload": false, "replayCount": 0, "autoReplayCount": 0, "createdAt": "2026-09-29T21:18:47Z", "updatedAt": "2026-09-29T21:19:02Z"}], "nextCursor": "eyJ2IjoxLCJrIjpbXX0"}
                            """)))
    PagedResponse<DeadLetterItemResponse> listDeadLetters(
            @RequestParam(required = false) @Nullable List<String> status,
            @RequestParam(required = false) @Nullable List<String> source,
            @RequestParam(required = false) @Nullable List<String> stage,
            @RequestParam(required = false) @Nullable List<String> category,
            @RequestParam(required = false) @Nullable List<String> severity,
            @RequestParam(required = false) @Nullable List<String> ruleId,
            @RequestParam(required = false) @Nullable String from,
            @RequestParam(required = false) @Nullable String to,
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String cursor) {
        String fingerprint = CursorCodec.fingerprint(RequestValues.filters(
                "status",
                status,
                "source",
                source,
                "stage",
                stage,
                "category",
                category,
                "severity",
                severity,
                "ruleId",
                ruleId,
                "from",
                from,
                "to",
                to));
        PageRequest page = paging.resolve(limit, cursor, fingerprint);
        TimeRanges.Bounds bounds = ranges.bounds(from, to);
        DeadLetterFilter filter = new DeadLetterFilter(
                RequestValues.allOf("status", status, STATUSES),
                RequestValues.allOf("source", source, SOURCES),
                RequestValues.allOf("stage", stage, STAGES),
                RequestValues.allOf("category", category, CATEGORIES),
                RequestValues.allOf("severity", severity, SEVERITIES),
                RequestValues.distinct("ruleId", ruleId),
                bounds.from(),
                bounds.to());
        return paging.respond(listDeadLetters.execute(filter, page), DeadLetterItemResponse::from, fingerprint);
    }

    @GetMapping(ApiPaths.V1 + "/etl/dlq/summary")
    @Operation(
            operationId = "getDeadLetterSummary",
            summary = "How many dead letters are open, by status, source and severity (viewer)")
    @ApiResponse(
            responseCode = "200",
            description = "The counters; open means not REPLAYED, DISCARDED or RESOLVED",
            content = @Content(examples = @ExampleObject(name = "summary", value = """
                            {"open": 214, "byStatus": {"NEW": 3, "TRIAGING": 1, "TRIAGED": 0, "AUTO_REPLAY_SCHEDULED": 2, "PENDING_CONFIRM": 9, "MANUAL": 199, "REPLAY_REQUESTED": 0}, "openBySource": {"GTFS_RT_VEHICLE_POSITION": 180, "GTFS_RT_TRIP_UPDATE": 30, "TICKETING_SALES": 4}, "openBySeverity": {"0": 20, "1": 60, "2": 120, "unclassified": 14}, "createdLastHour": 37}
                            """)))
    DeadLetterSummaryResponse getDeadLetterSummary() {
        return DeadLetterSummaryResponse.from(getDeadLetterSummary.execute());
    }

    @GetMapping(ApiPaths.V1 + "/etl/dlq/actions")
    @Operation(
            operationId = "listDeadLetterActions",
            summary = "The log of what was done to dead letters, by people and by automation (viewer)")
    @ApiResponse(
            responseCode = "200",
            description = "A page of log lines of the window (default 24 hours), newest first",
            content = @Content(examples = @ExampleObject(name = "actions", value = """
                            {"items": [{"id": 8812, "deadLetterId": "0192f5b2-8d9e-7a0b-9c1d-2e3f4a5b6c7d", "action": "REPLAY_REQUESTED", "actor": "user:operator", "details": {"replay_request_id": "0192f5c0-0000-7000-8000-000000000001"}, "at": "2026-09-29T21:30:00Z", "source": "GTFS_RT_VEHICLE_POSITION", "status": "REPLAY_REQUESTED"}]}
                            """)))
    PagedResponse<ActionLogResponse> listDeadLetterActions(
            @RequestParam(required = false) @Nullable String from,
            @RequestParam(required = false) @Nullable String to,
            @RequestParam(required = false) @Nullable List<String> action,
            @RequestParam(required = false) @Nullable String actorType,
            @RequestParam(required = false) @Nullable String deadLetterId,
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String cursor) {
        String fingerprint = CursorCodec.fingerprint(RequestValues.filters(
                "from", from, "to", to, "action", action, "actorType", actorType, "deadLetterId", deadLetterId));
        PageRequest page = paging.resolve(limit, cursor, fingerprint);
        TimeRanges.Range range = ranges.resolveThroughNow(from, to, ACTIONS_SPAN);
        String type = RequestValues.oneOf("actorType", actorType, List.of("auto", "system", "user"));
        ActionLogFilter filter = new ActionLogFilter(
                range.from(),
                range.to(),
                RequestValues.allOf("action", action, ACTIONS),
                type != null ? ActionLogFilter.ActorType.valueOf(type.toUpperCase(Locale.ROOT)) : null,
                deadLetterId != null ? RequestValues.uuid("deadLetterId", deadLetterId) : null);
        return paging.respond(listDeadLetterActions.execute(filter, page), ActionLogResponse::from, fingerprint);
    }

    @GetMapping(ApiPaths.V1 + "/etl/dlq/{id}")
    @Operation(
            operationId = "getDeadLetter",
            summary = "One dead letter with its payloads, action log, replays and the actions allowed now")
    @ApiResponse(
            responseCode = "200",
            description = "The dead letter; allowedActions is empty for a viewer",
            content = @Content(examples = @ExampleObject(name = "deadLetter", value = """
                            {"id": "0192f5b2-8d9e-7a0b-9c1d-2e3f4a5b6c7d", "source": "GTFS_RT_VEHICLE_POSITION", "stage": "SCHEMA", "ruleId": "DQ-01", "errorClass": "SchemaViolationException", "errorMessage": "$.payload.position.latitude: must be between -90 and 90", "status": "MANUAL", "category": "schema_violation", "categoryConfidence": 0.412, "severity": 2, "severityConfidence": 0.655, "businessKey": "1203|2026-09-29T21:18:45Z", "hasEditedPayload": false, "replayCount": 0, "autoReplayCount": 0, "createdAt": "2026-09-29T21:18:47Z", "updatedAt": "2026-09-29T21:19:02Z", "rawPayload": "{\\"schema_version\\":1}", "kafka": {"topic": "gtfs.vehicle_positions", "partition": 2, "offset": 8123411, "timestamp": "2026-09-29T21:18:46Z"}, "batchId": "0192f5a1-7c1e-7d3a-9b2c-4e5f6a7b8c9d", "triageAttempts": 1, "actions": [{"at": "2026-09-29T21:19:02Z", "action": "MANUAL_REQUIRED", "actor": "auto", "details": {}}], "replays": [], "allowedActions": ["edit", "replay", "discard", "resolve"]}
                            """)))
    @ApiResponse(responseCode = "404", description = "No such dead letter")
    DeadLetterDetailResponse getDeadLetter(Caller caller, @PathVariable String id) {
        return DeadLetterDetailResponse.from(
                getDeadLetter.execute(caller, RequestValues.uuidOrNotFound(id, "The dead letter")));
    }
}
