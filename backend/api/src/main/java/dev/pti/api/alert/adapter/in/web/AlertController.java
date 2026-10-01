package dev.pti.api.alert.adapter.in.web;

import dev.pti.api.alert.application.AcknowledgeAlert;
import dev.pti.api.alert.application.AlertQuery;
import dev.pti.api.alert.application.ListAlerts;
import dev.pti.api.alert.domain.Alert;
import dev.pti.api.alert.domain.AlertState;
import dev.pti.api.alert.domain.AlertType;
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
import dev.pti.api.platform.domain.ValidationException;
import dev.pti.api.platform.domain.WithAsOf;
import dev.pti.common.events.Audience;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * E-20 {@code GET /alerts} and E-21 {@code POST /alerts/{id}/ack} (DOC-32 §5). The list is open to everyone but
 * shows an anonymous caller only the public alerts, in their public projection; it differs by caller and so says
 * {@code Vary: Authorization}. The time axis is audit time: an alert is ordered by when it was raised.
 */
@RestController
class AlertController {

    private static final Duration DEFAULT_SPAN = Duration.ofHours(24);

    private final ListAlerts listAlerts;
    private final AcknowledgeAlert acknowledgeAlert;
    private final TimeRanges timeRanges;
    private final PageParams paging;

    AlertController(
            ListAlerts listAlerts,
            AcknowledgeAlert acknowledgeAlert,
            @Qualifier("auditTimeRanges") TimeRanges timeRanges,
            PageParams paging) {
        this.listAlerts = listAlerts;
        this.acknowledgeAlert = acknowledgeAlert;
        this.timeRanges = timeRanges;
        this.paging = paging;
    }

    @GetMapping(ApiPaths.V1 + "/alerts")
    @Operation(
            operationId = "listAlerts",
            summary = "Alerts in a time range, newest first (public ones for anonymous, every audience for viewers)")
    @ApiResponse(
            responseCode = "200",
            description = "One page of alerts",
            content = @Content(examples = @ExampleObject(name = "disruption", value = """
                            {"items": [{"id": "0a4c7e1f-2b3d-5e6f-8a9b-1c2d3e4f5a6b", "type": "DISRUPTION", "severity": 1, "audience": "PUBLIC", "routeId": "18", "refTable": "insight.insight_service_disruption", "refId": "9d8e7f6a-5b4c-5d3e-8f2a-1b0c9d8e7f6a", "title": "Delays on route 18 northbound", "body": {"disruptionId": "9d8e7f6a-5b4c-5d3e-8f2a-1b0c9d8e7f6a", "directionId": 0, "currentAvgDelaySeconds": 212.7}, "createdAt": "2026-09-29T20:59:31Z", "acknowledgedBy": "user:operator", "acknowledgedAt": "2026-09-29T21:02:10Z", "link": "/map?route=18&disruption=9d8e7f6a-5b4c-5d3e-8f2a-1b0c9d8e7f6a"}], "nextCursor": "eyJ2IjoxLCJrIjpbIjIwMjYtMDktMjlUMjA6NTk6MzFaIiwiMGE0YzdlMWYtMmIzZC01ZTZmLThhOWItMWMyZDNlNGY1YTZiIl0sImYiOiI5ZjJjIn0"}
                            """)))
    @ApiResponse(responseCode = "400", description = "A parameter is not valid")
    @ApiResponse(
            responseCode = "403",
            description = "An anonymous caller asked for the audience OPERATIONS or ENGINEERING")
    ResponseEntity<PagedResponse<AlertResponse>> listAlerts(
            Caller caller,
            @RequestParam(required = false) @Nullable List<String> audience,
            @RequestParam(required = false) @Nullable List<String> type,
            @RequestParam(required = false) @Nullable List<String> severity,
            @RequestParam(required = false) @Nullable List<String> routeId,
            @RequestParam(required = false) @Nullable String state,
            @RequestParam(required = false) @Nullable String since,
            @RequestParam(required = false) @Nullable String from,
            @RequestParam(required = false) @Nullable String to,
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String cursor) {
        Set<Audience> audiences = audiences(audience);
        List<AlertType> types = RequestValues.allOf("type", type, AlertType.names()).stream()
                .map(AlertType::valueOf)
                .toList();
        List<Integer> severities = RequestValues.ints("severity", severity, 0, 2);
        List<String> routeIds = RequestValues.distinct("routeId", routeId);
        AlertState alertState = state == null || state.isBlank()
                ? AlertState.ALL
                : AlertState.fromWireName(state)
                        .orElseThrow(() -> ValidationException.of(
                                "state", "must be one of " + String.join(", ", AlertState.wireNames())));
        if (since != null && from != null) {
            throw ValidationException.of("since", "cannot be combined with from");
        }
        Instant sinceInstant = since == null ? null : timeRanges.point("since", since);
        TimeRanges.Range range = sinceInstant == null
                ? timeRanges.resolveThroughNow(from, to, DEFAULT_SPAN)
                : timeRanges.resolveThroughNow(sinceInstant.toString(), to, DEFAULT_SPAN);
        String fingerprint = CursorCodec.fingerprint(RequestValues.filters(
                "audience", audiences.stream().map(Audience::name).toList(),
                "type", types,
                "severity", severities,
                "routeId", routeIds,
                "state", alertState.wireName(),
                "since", since,
                "from", from,
                "to", to));
        PageRequest request = paging.resolve(limit, cursor, fingerprint);
        AlertQuery query = new AlertQuery(
                audiences, types, severities, routeIds, alertState, range.from(), range.to(), sinceInstant);
        WithAsOf<Page<Alert>> result = listAlerts.execute(caller, query, request);
        return ResponseEntity.ok()
                .header(HttpHeaders.VARY, HttpHeaders.AUTHORIZATION)
                .headers(DataAsOfHeader.of(result.asOf()))
                .body(paging.respond(result.value(), AlertResponse::from, fingerprint));
    }

    @PostMapping(ApiPaths.V1 + "/alerts/{id}/ack")
    @Operation(
            operationId = "acknowledgeAlert",
            summary = "Acknowledge an alert; idempotent, the first operator to acknowledge stays (operator)")
    @ApiResponse(
            responseCode = "200",
            description = "The alert as it is now, acknowledged",
            content = @Content(examples = @ExampleObject(name = "acknowledged", value = """
                            {"id": "0a4c7e1f-2b3d-5e6f-8a9b-1c2d3e4f5a6b", "type": "BUNCHING", "severity": 1, "audience": "OPERATIONS", "routeId": "18", "refTable": "insight.insight_bus_bunching", "refId": "6f1c2a9e-4b1d-5c8e-9a2f-3d4e5f6a7b8c", "title": "Bus bunching on route 18 Northbound: vehicles 1187 and 1203", "body": {"bunchingId": "6f1c2a9e-4b1d-5c8e-9a2f-3d4e5f6a7b8c"}, "createdAt": "2026-09-29T21:12:31Z", "acknowledgedBy": "user:operator", "acknowledgedAt": "2026-09-29T21:14:02Z", "link": "/map?route=18&bunching=6f1c2a9e-4b1d-5c8e-9a2f-3d4e5f6a7b8c"}
                            """)))
    @ApiResponse(responseCode = "404", description = "There is no such alert")
    AlertResponse acknowledgeAlert(Caller caller, @PathVariable String id) {
        return AlertResponse.from(
                acknowledgeAlert.execute(caller.actor(), RequestValues.uuidOrNotFound(id, "The alert")));
    }

    private static Set<Audience> audiences(@Nullable List<String> values) {
        List<String> names = RequestValues.allOf(
                "audience",
                values,
                Arrays.stream(Audience.values()).map(Enum::name).toList());
        Set<Audience> audiences = EnumSet.noneOf(Audience.class);
        names.forEach(name -> audiences.add(Audience.valueOf(name)));
        return audiences;
    }
}
