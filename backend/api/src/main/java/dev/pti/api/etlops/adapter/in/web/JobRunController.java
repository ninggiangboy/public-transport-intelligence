package dev.pti.api.etlops.adapter.in.web;

import dev.pti.api.etlops.adapter.in.web.JobResponses.JobRunDetailResponse;
import dev.pti.api.etlops.adapter.in.web.JobResponses.JobRunResponse;
import dev.pti.api.etlops.adapter.in.web.JobResponses.JobSummaryResponse;
import dev.pti.api.etlops.application.GetJobRun;
import dev.pti.api.etlops.application.GetJobSummary;
import dev.pti.api.etlops.application.ListJobRuns;
import dev.pti.api.etlops.domain.GrafanaLinks;
import dev.pti.api.etlops.domain.JobRunFilter;
import dev.pti.api.etlops.domain.RunKind;
import dev.pti.api.etlops.domain.SummaryBucket;
import dev.pti.api.platform.adapter.in.web.ApiPaths;
import dev.pti.api.platform.adapter.in.web.CursorCodec;
import dev.pti.api.platform.adapter.in.web.PageParams;
import dev.pti.api.platform.adapter.in.web.PagedResponse;
import dev.pti.api.platform.adapter.in.web.RequestValues;
import dev.pti.api.platform.adapter.in.web.TimeRanges;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;
import dev.pti.api.platform.domain.ValidationException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * E-30 {@code GET /etl/jobs}, E-31 {@code GET /etl/jobs/summary} and E-32 {@code GET /etl/jobs/{runId}} (DOC-32 §6):
 * the runs of the ETL, batch jobs and minutes of streaming micro-batches, for the Jobs screen. They are for a viewer,
 * on the audit axis, and never cached.
 */
@RestController
class JobRunController {

    private static final Duration DEFAULT_SPAN = Duration.ofHours(1);
    private static final List<String> BATCH_STATUSES =
            List.of("STARTING", "STARTED", "STOPPING", "STOPPED", "FAILED", "COMPLETED", "ABANDONED", "UNKNOWN");
    private static final List<String> STREAM_STATUSES = List.of("COMPLETED", "COMPLETED_WITH_SKIPS", "FAILED");
    private static final List<String> STATUSES = Stream.concat(BATCH_STATUSES.stream(), STREAM_STATUSES.stream())
            .distinct()
            .toList();

    private final ListJobRuns listJobRuns;
    private final GetJobSummary getJobSummary;
    private final GetJobRun getJobRun;
    private final PageParams paging;
    private final TimeRanges ranges;
    private final GrafanaLinks grafana;

    JobRunController(
            ListJobRuns listJobRuns,
            GetJobSummary getJobSummary,
            GetJobRun getJobRun,
            PageParams paging,
            @Qualifier("opsTimeRanges") TimeRanges ranges,
            GrafanaLinks grafana) {
        this.listJobRuns = listJobRuns;
        this.getJobSummary = getJobSummary;
        this.getJobRun = getJobRun;
        this.paging = paging;
        this.ranges = ranges;
        this.grafana = grafana;
    }

    @GetMapping(ApiPaths.V1 + "/etl/jobs")
    @Operation(
            operationId = "listJobRuns",
            summary = "Runs of the ETL, batch jobs and minutes of micro-batches, newest first (viewer)")
    @ApiResponse(
            responseCode = "200",
            description = "A page of runs started in the window (default 1 hour, at most 24 hours)",
            content = @Content(examples = @ExampleObject(name = "runs", value = """
                            {"items": [{"runId": "job:4127", "kind": "BATCH_JOB", "name": "RawZoneReplayJob", "status": "FAILED", "exitCode": "FAILED", "exitMessage": "Execution became stale", "startedAt": "2026-09-29T20:40:02Z", "endedAt": "2026-09-29T20:52:11Z", "durationMs": 729000, "readCount": 412000, "writeCount": 411050, "skipCount": 950, "jobExecutionId": 4127, "batchIds": ["0192f5a1-7c1e-7d3a-9b2c-4e5f6a7b8c9d"], "restartable": true, "request": {"type": "replay", "id": "0192f5a0-1b2c-7d3e-8f4a-5b6c7d8e9f0a", "requestedBy": "user:operator"}}, {"runId": "stream:gtfs-rt-vehicle-position:2026-09-29T21:18Z", "kind": "STREAM", "name": "gtfs-rt-vehicle-position", "status": "COMPLETED_WITH_SKIPS", "startedAt": "2026-09-29T21:18:00Z", "endedAt": "2026-09-29T21:18:59.870Z", "durationMs": 59870, "readCount": 7272, "writeCount": 7268, "skipCount": 4, "duplicateCount": 0, "batchCount": 60}], "nextCursor": "eyJ2IjoxLCJrIjpbXX0"}
                            """)))
    PagedResponse<JobRunResponse> listJobRuns(
            @RequestParam(required = false) @Nullable String from,
            @RequestParam(required = false) @Nullable String to,
            @RequestParam(required = false) @Nullable String kind,
            @RequestParam(required = false) @Nullable List<String> name,
            @RequestParam(required = false) @Nullable List<String> status,
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String cursor) {
        String fingerprint = CursorCodec.fingerprint(
                RequestValues.filters("from", from, "to", to, "kind", kind, "name", name, "status", status));
        PageRequest page = paging.resolve(limit, cursor, fingerprint);
        TimeRanges.Range range = ranges.resolveThroughNow(from, to, DEFAULT_SPAN);
        String chosenKind = RequestValues.oneOf(
                "kind", kind, Arrays.stream(RunKind.values()).map(RunKind::name).toList());
        JobRunFilter filter = new JobRunFilter(
                range.from(),
                range.to(),
                chosenKind != null ? RunKind.valueOf(chosenKind) : null,
                RequestValues.distinct("name", name),
                RequestValues.allOf("status", status, STATUSES));
        Page<dev.pti.api.etlops.domain.JobRun> result = listJobRuns.execute(filter, page);
        return paging.respond(result, JobRunResponse::from, fingerprint);
    }

    @GetMapping(ApiPaths.V1 + "/etl/jobs/summary")
    @Operation(
            operationId = "getJobSummary",
            summary = "Read, written and skipped records per source over time, and batch jobs by outcome (viewer)")
    @ApiResponse(
            responseCode = "200",
            description = "A series per stream source with every bucket of the window, zeros included",
            content = @Content(examples = @ExampleObject(name = "summary", value = """
                            {"bucket": "1m", "from": "2026-09-29T20:20:00Z", "to": "2026-09-29T21:20:00Z", "stream": [{"source": "GTFS_RT_VEHICLE_POSITION", "points": [{"bucketStart": "2026-09-29T21:18:00Z", "batches": 60, "failedBatches": 0, "read": 7272, "written": 7268, "skipped": 4, "duplicate": 0, "p95BatchMs": 212}]}], "batchJobs": {"running": 1, "completed": 14, "failed": 1, "stopped": 0}}
                            """)))
    JobSummaryResponse getJobSummary(
            @RequestParam(required = false) @Nullable String from,
            @RequestParam(required = false) @Nullable String to,
            @RequestParam(required = false) @Nullable String bucket) {
        SummaryBucket size = bucket == null
                ? SummaryBucket.ONE_MINUTE
                : SummaryBucket.fromWire(bucket)
                        .orElseThrow(() -> ValidationException.of("bucket", "must be one of 1m, 5m, 15m, 1h"));
        TimeRanges.Range range = ranges.resolve(from, to, DEFAULT_SPAN);
        return JobSummaryResponse.from(getJobSummary.execute(range.from(), range.to(), size));
    }

    @GetMapping(ApiPaths.V1 + "/etl/jobs/{runId}")
    @Operation(
            operationId = "getJobRun",
            summary = "One run in full: parameters, steps and request of a batch job, micro-batches of a stream run")
    @ApiResponse(
            responseCode = "200",
            description = "The run",
            content = @Content(examples = @ExampleObject(name = "batchJob", value = """
                            {"runId": "job:4127", "kind": "BATCH_JOB", "name": "RawZoneReplayJob", "status": "FAILED", "exitCode": "FAILED", "exitMessage": "Execution became stale", "startedAt": "2026-09-29T20:40:02Z", "endedAt": "2026-09-29T20:52:11Z", "jobExecutionId": 4127, "restartable": true, "parameters": [{"name": "replayRequestId", "type": "java.lang.String", "value": "0192f5a0-1b2c-7d3e-8f4a-5b6c7d8e9f0a", "identifying": true}], "steps": [{"stepExecutionId": 99121, "stepName": "replayRecords", "status": "FAILED", "readCount": 412000, "writeCount": 411050, "filterCount": 0, "readSkipCount": 0, "processSkipCount": 950, "writeSkipCount": 0, "commitCount": 823, "rollbackCount": 1, "batchId": "0192f5a1-7c1e-7d3a-9b2c-4e5f6a7b8c9d"}], "request": {"type": "replay", "id": "0192f5a0-1b2c-7d3e-8f4a-5b6c7d8e9f0a", "requestedBy": "user:operator"}, "links": {"trace": "http://localhost:3000/explore?left=%7B%7D", "logs": "http://localhost:3000/explore?left=%7B%7D"}}
                            """)))
    @ApiResponse(
            responseCode = "404",
            description = "No such run, or an id that is not of the form job:<n> or stream:<listener>:<minute>")
    JobRunDetailResponse getJobRun(@PathVariable String runId) {
        return JobRunDetailResponse.from(getJobRun.execute(runId), grafana);
    }
}
