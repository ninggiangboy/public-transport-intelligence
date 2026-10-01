package dev.pti.api.insight.adapter.in.web;

import dev.pti.api.insight.application.GetTicketingAnomaly;
import dev.pti.api.insight.application.ListTicketingAnomalies;
import dev.pti.api.insight.application.TicketingQuery;
import dev.pti.api.insight.domain.TicketingAnomaly;
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
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * E-15 {@code GET /insights/ticketing-anomalies} and E-16 {@code GET /insights/ticketing-anomalies/{id}} (DOC-32 §4),
 * for viewers. The table stays empty until the ticketing analytics of P6 write to it.
 */
@RestController
class TicketingController {

    private static final Duration DEFAULT_SPAN = Duration.ofHours(24);
    private static final List<String> CATEGORIES = List.of("fraud_suspect", "system_error", "promo_spike", "normal");
    private static final String UNCLASSIFIED = "unclassified";
    private static final List<String> TRIGGERS = List.of("VOLUME", "REFUND_RATIO", "BOTH");

    private final ListTicketingAnomalies listAnomalies;
    private final GetTicketingAnomaly getAnomaly;
    private final TimeRanges timeRanges;
    private final PageParams paging;
    private final JsonMapper json;

    TicketingController(
            ListTicketingAnomalies listAnomalies,
            GetTicketingAnomaly getAnomaly,
            @Qualifier("eventTimeRanges") TimeRanges timeRanges,
            PageParams paging,
            JsonMapper json) {
        this.listAnomalies = listAnomalies;
        this.getAnomaly = getAnomaly;
        this.timeRanges = timeRanges;
        this.paging = paging;
        this.json = json;
    }

    @GetMapping(ApiPaths.V1 + "/insights/ticketing-anomalies")
    @Operation(
            operationId = "listTicketingAnomalies",
            summary = "Ticketing anomalies detected in a time range, newest first (viewer)")
    @ApiResponse(
            responseCode = "200",
            description = "One page of anomalies",
            content = @Content(examples = @ExampleObject(name = "refund ratio", value = """
                            {"items": [{"id": "c1d2e3f4-a5b6-5c7d-8e9f-0a1b2c3d4e5f", "salePointId": "SP-0142", "salePointName": "Nicollet Mall Station kiosk 2", "routeId": "18", "windowStart": "2026-09-29T21:00:00Z", "windowEnd": "2026-09-29T21:15:00Z", "detectedAt": "2026-09-29T21:15:00Z", "trigger": "REFUND_RATIO", "txnCount": 25, "refundCount": 12, "refundRatio": 0.4800, "amountSum": 50.00, "baselineMean": 14.20, "baselineStddev": 3.10, "zScore": 3.48, "enrichmentStatus": "DONE", "category": "fraud_suspect", "categoryConfidence": 0.77, "severity": 2, "severityConfidence": 0.69, "modelVersion": "jev@0.2.0"}], "nextCursor": "eyJ2IjoxLCJrIjpbIjIwMjYtMDktMjlUMjE6MTU6MDBaIiwiYzFkMmUzZjQtYTViNi01YzdkLThlOWYtMGExYjJjM2Q0ZTVmIl0sImYiOiI5ZjJjIn0"}
                            """)))
    @ApiResponse(responseCode = "400", description = "A parameter is not valid")
    ResponseEntity<PagedResponse<TicketingAnomalyResponse>> listTicketingAnomalies(
            @RequestParam(required = false) @Nullable String from,
            @RequestParam(required = false) @Nullable String to,
            @RequestParam(required = false) @Nullable String salePointId,
            @RequestParam(required = false) @Nullable List<String> category,
            @RequestParam(required = false) @Nullable List<String> severity,
            @RequestParam(required = false) @Nullable String trigger,
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String cursor) {
        List<String> categoryValues = RequestValues.allOf("category", category, withUnclassified());
        boolean unclassified = categoryValues.contains(UNCLASSIFIED);
        List<String> categories = categoryValues.stream()
                .filter(value -> !UNCLASSIFIED.equals(value))
                .toList();
        List<Integer> severities = RequestValues.ints("severity", severity, 0, 2);
        String triggerFilter = RequestValues.oneOf("trigger", trigger, TRIGGERS);
        String salePoint = salePointId == null || salePointId.isBlank() ? null : salePointId;
        TimeRanges.Range range = timeRanges.resolveThroughNow(from, to, DEFAULT_SPAN);
        String fingerprint = CursorCodec.fingerprint(RequestValues.filters(
                "from", from,
                "to", to,
                "salePointId", salePoint,
                "category", categoryValues,
                "severity", severities,
                "trigger", triggerFilter));
        PageRequest request = paging.resolve(limit, cursor, fingerprint);
        WithAsOf<Page<TicketingAnomaly>> result = listAnomalies.execute(
                new TicketingQuery(
                        range.from(), range.to(), salePoint, categories, unclassified, severities, triggerFilter),
                request);
        return ResponseEntity.ok()
                .headers(DataAsOfHeader.of(result.asOf()))
                .body(paging.respond(
                        result.value(), anomaly -> TicketingAnomalyResponse.from(anomaly, json), fingerprint));
    }

    @GetMapping(ApiPaths.V1 + "/insights/ticketing-anomalies/{id}")
    @Operation(
            operationId = "getTicketingAnomaly",
            summary = "One ticketing anomaly with its window summary and batch (viewer)")
    @ApiResponse(
            responseCode = "200",
            description = "The anomaly; summary holds no personal data",
            content = @Content(examples = @ExampleObject(name = "pending enrichment", value = """
                            {"id": "c1d2e3f4-a5b6-5c7d-8e9f-0a1b2c3d4e5f", "salePointId": "SP-0142", "windowStart": "2026-09-29T21:00:00Z", "windowEnd": "2026-09-29T21:15:00Z", "detectedAt": "2026-09-29T21:15:00Z", "trigger": "VOLUME", "txnCount": 61, "refundCount": 1, "refundRatio": 0.0164, "amountSum": 122.00, "enrichmentStatus": "PENDING", "summary": {"txnCount": 61, "refundCount": 1}, "batchId": "0b9d3c52-6a1f-4c1e-9f0a-2a7d1e4c8b10"}
                            """)))
    @ApiResponse(responseCode = "404", description = "There is no such anomaly")
    ResponseEntity<TicketingAnomalyResponse> getTicketingAnomaly(@PathVariable String id) {
        WithAsOf<TicketingAnomaly> result =
                getAnomaly.execute(RequestValues.uuidOrNotFound(id, "The ticketing anomaly"));
        return ResponseEntity.ok()
                .headers(DataAsOfHeader.of(result.asOf()))
                .body(TicketingAnomalyResponse.from(result.value(), json));
    }

    private static List<String> withUnclassified() {
        List<String> allowed = new ArrayList<>(CATEGORIES);
        allowed.add(UNCLASSIFIED);
        return allowed;
    }
}
