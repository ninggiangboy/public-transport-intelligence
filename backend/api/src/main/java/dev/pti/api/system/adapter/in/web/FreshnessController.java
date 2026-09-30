package dev.pti.api.system.adapter.in.web;

import dev.pti.api.platform.adapter.in.web.ApiPaths;
import dev.pti.api.platform.adapter.in.web.DataAsOfHeader;
import dev.pti.api.system.application.GetFreshness;
import dev.pti.api.system.domain.Freshness;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** E-60 {@code GET /system/freshness} (DOC-32): the stale banner, the business clock and the ACTIVE feed. */
@RestController
class FreshnessController {

    private final GetFreshness getFreshness;

    FreshnessController(GetFreshness getFreshness) {
        this.getFreshness = getFreshness;
    }

    @GetMapping(ApiPaths.V1 + "/system/freshness")
    @Operation(
            operationId = "getFreshness",
            summary = "Age of each data source, the stale flag, the business clock and the active GTFS feed")
    @ApiResponse(
            responseCode = "200",
            description = "The result of the last freshness probe",
            content = @Content(examples = @ExampleObject(name = "fresh", value = """
                                                    {"businessNow": "2026-09-29T21:19:35Z", "clockOffset": "PT0S", "checkedAt": "2026-09-29T21:19:30Z", "stale": false, "activeFeed": {"feedVersionId": 3, "publisherFeedVersion": "2026-08-23", "timezone": "America/Chicago", "validFrom": "2026-08-23", "validTo": "2026-12-12", "activatedAt": "2026-09-27T08:34:40Z"}, "sources": [{"source": "GTFS_RT_VEHICLE_POSITION", "lastEventAt": "2026-09-29T21:19:30Z", "ageSeconds": 5, "staleAfterSeconds": 120, "stale": false}], "insights": {"etaComputedAt": "2026-09-29T21:05:12Z", "otpComputedAt": "2026-09-29T08:00:41Z"}}
                                                    """)))
    ResponseEntity<FreshnessResponse> getFreshness() {
        Freshness freshness = getFreshness.execute();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .headers(DataAsOfHeader.of(freshness.checkedAt()))
                .body(FreshnessResponse.from(freshness));
    }
}
