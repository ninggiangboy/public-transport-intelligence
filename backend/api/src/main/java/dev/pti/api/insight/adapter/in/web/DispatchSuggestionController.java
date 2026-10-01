package dev.pti.api.insight.adapter.in.web;

import dev.pti.api.insight.application.ListDispatchSuggestions;
import dev.pti.api.insight.application.SubmitDispatchFeedback;
import dev.pti.api.insight.application.SuggestionQuery;
import dev.pti.api.insight.config.DispatchProperties;
import dev.pti.api.insight.domain.DispatchSuggestion;
import dev.pti.api.insight.domain.Feedback;
import dev.pti.api.platform.adapter.in.web.ApiPaths;
import dev.pti.api.platform.adapter.in.web.CursorCodec;
import dev.pti.api.platform.adapter.in.web.PageParams;
import dev.pti.api.platform.adapter.in.web.PagedResponse;
import dev.pti.api.platform.adapter.in.web.RequestValues;
import dev.pti.api.platform.adapter.in.web.TimeRanges;
import dev.pti.api.platform.domain.Caller;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;
import dev.pti.api.platform.domain.ValidationException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * E-17 {@code GET /insights/dispatch-suggestions} (viewer) and E-18 {@code POST
 * /insights/dispatch-suggestions/{id}/feedback} (operator), DOC-32 §4. The time axis of the list is audit time: a
 * suggestion is ordered by when it was made, not by the event it is about.
 */
@RestController
class DispatchSuggestionController {

    private static final Duration DEFAULT_SPAN = Duration.ofHours(24);
    private static final String NONE = "none";

    private final ListDispatchSuggestions listSuggestions;
    private final SubmitDispatchFeedback submitFeedback;
    private final TimeRanges timeRanges;
    private final PageParams paging;
    private final DispatchProperties dispatch;
    private final JsonMapper json;

    DispatchSuggestionController(
            ListDispatchSuggestions listSuggestions,
            SubmitDispatchFeedback submitFeedback,
            @Qualifier("auditTimeRanges") TimeRanges timeRanges,
            PageParams paging,
            DispatchProperties dispatch,
            JsonMapper json) {
        this.listSuggestions = listSuggestions;
        this.submitFeedback = submitFeedback;
        this.timeRanges = timeRanges;
        this.paging = paging;
        this.dispatch = dispatch;
        this.json = json;
    }

    @GetMapping(ApiPaths.V1 + "/insights/dispatch-suggestions")
    @Operation(
            operationId = "listDispatchSuggestions",
            summary = "Dispatch suggestions created in a time range, newest first (viewer)")
    @ApiResponse(
            responseCode = "200",
            description = "One page of suggestions; lowConfidence is true below pti.api.dispatch.low-confidence",
            content = @Content(examples = @ExampleObject(name = "one suggestion", value = """
                            {"items": [{"id": "3b2a1c0d-9e8f-4a7b-8c6d-5e4f3a2b1c0d", "bunchingId": "6f1c2a9e-4b1d-5c8e-9a2f-3d4e5f6a7b8c", "routeId": "18", "action": "hold_follower", "actionConfidence": 0.82, "lowConfidence": false, "modelVersion": "jev@0.2.0", "createdAt": "2026-09-29T21:12:47Z", "stateSnapshot": {"task": "Suggest one dispatch action for a pair of buses running too close together on the same route."}}], "nextCursor": "eyJ2IjoxLCJrIjpbIjIwMjYtMDktMjlUMjE6MTI6NDdaIiwiM2IyYTFjMGQtOWU4Zi00YTdiLThjNmQtNWU0ZjNhMmIxYzBkIl0sImYiOiI5ZjJjIn0"}
                            """)))
    @ApiResponse(responseCode = "400", description = "A parameter is not valid")
    PagedResponse<DispatchSuggestionResponse> listDispatchSuggestions(
            @RequestParam(required = false) @Nullable String from,
            @RequestParam(required = false) @Nullable String to,
            @RequestParam(required = false) @Nullable List<String> routeId,
            @RequestParam(required = false) @Nullable String bunchingId,
            @RequestParam(required = false) @Nullable String feedback,
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String cursor) {
        List<String> routeIds = RequestValues.distinct("routeId", routeId);
        String feedbackFilter = RequestValues.oneOf("feedback", feedback, feedbackValues());
        UUID bunching =
                bunchingId == null || bunchingId.isBlank() ? null : RequestValues.uuid("bunchingId", bunchingId);
        TimeRanges.Range range = timeRanges.resolveThroughNow(from, to, DEFAULT_SPAN);
        String fingerprint = CursorCodec.fingerprint(RequestValues.filters(
                "from", from,
                "to", to,
                "routeId", routeIds,
                "bunchingId", bunching,
                "feedback", feedbackFilter));
        PageRequest request = paging.resolve(limit, cursor, fingerprint);
        Page<DispatchSuggestion> page = listSuggestions.execute(
                new SuggestionQuery(range.from(), range.to(), routeIds, bunching, feedbackFilter), request);
        return paging.respond(
                page,
                suggestion -> DispatchSuggestionResponse.from(suggestion, dispatch.lowConfidence(), json),
                fingerprint);
    }

    @PostMapping(ApiPaths.V1 + "/insights/dispatch-suggestions/{id}/feedback")
    @Operation(
            operationId = "submitDispatchFeedback",
            summary = "Accept or dismiss a dispatch suggestion; a later answer replaces an earlier one (operator)")
    @ApiResponse(
            responseCode = "200",
            description = "The suggestion as it is now, with the feedback",
            content = @Content(examples = @ExampleObject(name = "accepted", value = """
                            {"id": "3b2a1c0d-9e8f-4a7b-8c6d-5e4f3a2b1c0d", "bunchingId": "6f1c2a9e-4b1d-5c8e-9a2f-3d4e5f6a7b8c", "routeId": "18", "action": "hold_follower", "actionConfidence": 0.82, "lowConfidence": false, "modelVersion": "jev@0.2.0", "createdAt": "2026-09-29T21:12:47Z", "stateSnapshot": {}, "operatorFeedback": "accepted", "feedbackBy": "user:operator", "feedbackAt": "2026-09-29T21:14:02Z"}
                            """)))
    @ApiResponse(
            responseCode = "400",
            description = "The feedback is not accepted or ignored, or the body has other members")
    @ApiResponse(responseCode = "404", description = "There is no such suggestion")
    DispatchSuggestionResponse submitDispatchFeedback(
            Caller caller, @PathVariable String id, @Valid @RequestBody FeedbackRequest body) {
        Feedback feedback = Feedback.fromWireName(body.feedback())
                .orElseThrow(() -> ValidationException.of(
                        "feedback", "must be one of " + String.join(", ", Feedback.wireNames())));
        DispatchSuggestion suggestion = submitFeedback.execute(
                caller.actor(), RequestValues.uuidOrNotFound(id, "The dispatch suggestion"), feedback);
        return DispatchSuggestionResponse.from(suggestion, dispatch.lowConfidence(), json);
    }

    private static List<String> feedbackValues() {
        List<String> values = new ArrayList<>(Feedback.wireNames());
        values.add(NONE);
        return values;
    }
}
