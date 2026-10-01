package dev.pti.api.insight.adapter.in.web;

import dev.pti.api.insight.application.GetOtpScorecard;
import dev.pti.api.insight.application.OtpQuery;
import dev.pti.api.insight.domain.OtpScorecard;
import dev.pti.api.platform.adapter.in.web.ApiPaths;
import dev.pti.api.platform.adapter.in.web.DataAsOfHeader;
import dev.pti.api.platform.adapter.in.web.RequestValues;
import dev.pti.api.platform.domain.WithAsOf;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** E-14 {@code GET /insights/otp} (DOC-32 §4): the on-time performance of routes by service day, for viewers. */
@RestController
class OtpController {

    private static final CacheControl CACHE =
            CacheControl.maxAge(Duration.ofSeconds(60)).cachePrivate();

    private final GetOtpScorecard getScorecard;

    OtpController(GetOtpScorecard getScorecard) {
        this.getScorecard = getScorecard;
    }

    @GetMapping(ApiPaths.V1 + "/insights/otp")
    @Operation(
            operationId = "getOtpScorecard",
            summary = "On-time performance by route over service days, with a daily series, worst route first (viewer)")
    @ApiResponse(
            responseCode = "200",
            description = "A small list: one item per route that has observations in the range",
            content = @Content(examples = @ExampleObject(name = "seven days", value = """
                            {"fromDate": "2026-09-22", "toDate": "2026-09-28", "items": [{"routeId": "18", "otpPercentage": 78.41, "onTimeCount": 60311, "earlyCount": 2120, "lateCount": 14487, "observationCount": 76918, "tripCount": 2170, "earlyToleranceSeconds": 300, "lateToleranceSeconds": 300, "daily": [{"serviceDate": "2026-09-22", "otpPercentage": 80.02, "observationCount": 11020}]}]}
                            """)))
    @ApiResponse(responseCode = "400", description = "A parameter is not valid, or the range is longer than 31 days")
    @ApiResponse(responseCode = "503", description = "There is no active GTFS feed yet")
    ResponseEntity<OtpResponse> getOtpScorecard(
            @RequestParam(required = false) @Nullable String fromDate,
            @RequestParam(required = false) @Nullable String toDate,
            @RequestParam(required = false) @Nullable List<String> routeId,
            @RequestParam(required = false) @Nullable List<String> routeType) {
        LocalDate from = fromDate == null ? null : RequestValues.date("fromDate", fromDate);
        LocalDate to = toDate == null ? null : RequestValues.date("toDate", toDate);
        OtpQuery query = new OtpQuery(
                from,
                to,
                RequestValues.distinct("routeId", routeId),
                RequestValues.ints("routeType", routeType, 0, 1702));
        WithAsOf<OtpScorecard> result = getScorecard.execute(query);
        return ResponseEntity.ok()
                .cacheControl(CACHE)
                .headers(DataAsOfHeader.of(result.asOf()))
                .body(OtpResponse.from(result.value()));
    }
}
