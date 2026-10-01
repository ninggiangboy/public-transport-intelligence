package dev.pti.api.etlops.adapter.in.web;

import dev.pti.api.etlops.adapter.in.web.OpsResponses.BatchLineageResponse;
import dev.pti.api.etlops.adapter.in.web.OpsResponses.FeedVersionResponse;
import dev.pti.api.etlops.application.GetBatchLineage;
import dev.pti.api.etlops.application.ListFeedVersions;
import dev.pti.api.etlops.domain.GrafanaLinks;
import dev.pti.api.platform.adapter.in.web.ApiPaths;
import dev.pti.api.platform.adapter.in.web.ItemsResponse;
import dev.pti.api.platform.adapter.in.web.RequestValues;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * E-37 {@code GET /etl/batches/{batchId}} and E-38 {@code GET /etl/feeds} (DOC-32 §6): where a batch id comes from and
 * what it did, and the GTFS feed versions. Both are for a viewer and never cached.
 */
@RestController
class BatchController {

    private final GetBatchLineage getBatchLineage;
    private final ListFeedVersions listFeedVersions;
    private final GrafanaLinks grafana;

    BatchController(GetBatchLineage getBatchLineage, ListFeedVersions listFeedVersions, GrafanaLinks grafana) {
        this.getBatchLineage = getBatchLineage;
        this.listFeedVersions = listFeedVersions;
        this.grafana = grafana;
    }

    @GetMapping(ApiPaths.V1 + "/etl/batches/{batchId}")
    @Operation(
            operationId = "getBatchLineage",
            summary = "Trace a batch id to its micro-batch or step, its dead letters and its data quality results")
    @ApiResponse(
            responseCode = "200",
            description = "The batch; a micro-batch has its listener and offsets, a step its run, job and step",
            content = @Content(examples = @ExampleObject(name = "step", value = """
                            {"batchId": "0192f5a1-7c1e-7d3a-9b2c-4e5f6a7b8c9d", "origin": "BATCH_STEP", "runId": "job:4127", "jobName": "RawZoneReplayJob", "stepName": "replayRecords", "stepExecutionId": 99121, "startedAt": "2026-09-29T20:40:05Z", "endedAt": "2026-09-29T20:52:11Z", "counts": {"read": 412000, "written": 411050, "skipped": 950}, "replayRequestId": "0192f5a0-1b2c-7d3e-8f4a-5b6c7d8e9f0a", "deadLetters": {"total": 950, "byStatus": {"NEW": 12, "RESOLVED": 938}}, "dataQuality": [{"ruleId": "DQ-14", "tableName": "dw.fact_trip_update", "violationCount": 0, "checkedAt": "2026-09-29T20:52:30Z"}], "links": {"trace": "http://localhost:3000/explore?left=%7B%7D", "logs": "http://localhost:3000/explore?left=%7B%7D"}}
                            """)))
    @ApiResponse(responseCode = "404", description = "No micro-batch or step has this batch id")
    BatchLineageResponse getBatchLineage(@PathVariable String batchId) {
        return BatchLineageResponse.from(
                getBatchLineage.execute(RequestValues.uuidOrNotFound(batchId, "The batch")), grafana);
    }

    @GetMapping(ApiPaths.V1 + "/etl/feeds")
    @Operation(operationId = "listFeedVersions", summary = "The 50 newest GTFS feed versions (viewer)")
    @ApiResponse(
            responseCode = "200",
            description = "Feed versions, newest load first, with the totals of their validation report",
            content = @Content(examples = @ExampleObject(name = "feeds", value = """
                            {"items": [{"feedVersionId": 3, "feedHash": "9f2c0000000000000000000000000000000000000000000000000000000000aa", "status": "ACTIVE", "publisherName": "Metro Transit", "publisherFeedVersion": "2026-08-23", "agencyTimezone": "America/Chicago", "validFrom": "2026-08-23", "validTo": "2026-12-12", "loadedAt": "2026-09-27T08:31:12Z", "activatedAt": "2026-09-27T08:34:40Z", "runId": "job:3810", "validation": {"errors": 0, "warnings": 14}}]}
                            """)))
    ItemsResponse<FeedVersionResponse> listFeedVersions() {
        return new ItemsResponse<>(listFeedVersions.execute().stream()
                .map(FeedVersionResponse::from)
                .toList());
    }
}
