package dev.pti.api.etlops.adapter.in.web;

import dev.pti.api.etlops.adapter.in.web.JobResponses.JobRequestResponse;
import dev.pti.api.etlops.application.GetJobRequest;
import dev.pti.api.etlops.application.RequestJobRestart;
import dev.pti.api.etlops.application.RequestJobRun;
import dev.pti.api.etlops.application.RequestJobStop;
import dev.pti.api.etlops.application.Submitted;
import dev.pti.api.etlops.domain.IdempotencyKey;
import dev.pti.api.etlops.domain.JobRequest;
import dev.pti.api.platform.adapter.in.web.ApiPaths;
import dev.pti.api.platform.adapter.in.web.RequestValues;
import dev.pti.api.platform.domain.Caller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * E-33 {@code POST /etl/jobs}, E-34 {@code GET /etl/job-requests/{id}}, E-35 {@code POST /etl/jobs/{runId}/restart} and
 * E-36 {@code POST /etl/jobs/{runId}/stop} (DOC-32 §6): asking {@code etl-batch} to run, restart or stop a job. The API
 * only writes a request and answers 202 with where to look ({@code Location}); the {@code JobRequestPoller} acts on it
 * within five seconds (ADR-0013). The three writes take an {@code Idempotency-Key}.
 */
@RestController
class JobRequestController {

    private static final String EXAMPLE_REQUEST = """
            {"id": "0192f6c3-4d5e-7f6a-8b9c-0d1e2f3a4b5c", "kind": "RUN", "jobName": "OtpScorecardJob", "parameters": {"serviceDates": "2026-09-27+2026-09-28"}, "status": "PENDING", "requestedBy": "user:operator", "requestedAt": "2026-09-29T21:20:03Z"}
            """;

    /** The body of E-33. {@code parameters} holds texts, booleans and lists, as DOC-32 E-33 lists them per job. */
    record RunJobBody(@NotBlank String jobName, @Nullable Map<String, Object> parameters) {}

    private final RequestJobRun requestJobRun;
    private final GetJobRequest getJobRequest;
    private final RequestJobRestart requestJobRestart;
    private final RequestJobStop requestJobStop;

    JobRequestController(
            RequestJobRun requestJobRun,
            GetJobRequest getJobRequest,
            RequestJobRestart requestJobRestart,
            RequestJobStop requestJobStop) {
        this.requestJobRun = requestJobRun;
        this.getJobRequest = getJobRequest;
        this.requestJobRestart = requestJobRestart;
        this.requestJobStop = requestJobStop;
    }

    @PostMapping(ApiPaths.V1 + "/etl/jobs")
    @Operation(operationId = "requestJobRun", summary = "Run a job now (operator, Idempotency-Key)")
    @ApiResponse(
            responseCode = "202",
            description = "The request is stored; etl-batch starts the job within 5 seconds. Location is the request",
            content = @Content(examples = @ExampleObject(name = "request", value = EXAMPLE_REQUEST)))
    @ApiResponse(responseCode = "422", description = "job-not-allowed, or idempotency-key-reused")
    ResponseEntity<JobRequestResponse> requestJobRun(
            Caller caller,
            @RequestHeader(value = IdempotencyKey.HEADER, required = false) @Nullable String idempotencyKey,
            @Valid @RequestBody RunJobBody body) {
        Submitted<JobRequest> submitted = requestJobRun.execute(
                caller,
                body.jobName(),
                body.parameters() != null ? body.parameters() : Map.of(),
                IdempotencyKey.check(idempotencyKey));
        return accepted(submitted);
    }

    @GetMapping(ApiPaths.V1 + "/etl/job-requests/{id}")
    @Operation(operationId = "getJobRequest", summary = "Where a job request has got to (viewer)")
    @ApiResponse(
            responseCode = "200",
            description = "The request; once etl-batch has started it, also its run",
            content = @Content(examples = @ExampleObject(name = "running", value = """
                            {"id": "0192f6c3-4d5e-7f6a-8b9c-0d1e2f3a4b5c", "kind": "RUN", "jobName": "OtpScorecardJob", "parameters": {"serviceDates": "2026-09-27+2026-09-28"}, "status": "RUNNING", "requestedBy": "user:operator", "requestedAt": "2026-09-29T21:20:03Z", "jobExecutionId": 4130, "runId": "job:4130", "startedAt": "2026-09-29T21:20:05Z"}
                            """)))
    @ApiResponse(responseCode = "404", description = "No such request")
    JobRequestResponse getJobRequest(@PathVariable String id) {
        return JobRequestResponse.from(getJobRequest.execute(RequestValues.uuidOrNotFound(id, "The job request")));
    }

    @PostMapping(ApiPaths.V1 + "/etl/jobs/{runId}/restart")
    @Operation(
            operationId = "requestJobRestart",
            summary = "Restart a failed or stopped job (operator, Idempotency-Key)")
    @ApiResponse(
            responseCode = "202",
            description = "The request is stored; etl-batch restarts the execution within 5 seconds",
            content = @Content(examples = @ExampleObject(name = "request", value = """
                            {"id": "0192f6c4-0a1b-7c2d-8e3f-4a5b6c7d8e9f", "kind": "RESTART", "jobName": "RawZoneReplayJob", "parameters": {}, "status": "PENDING", "requestedBy": "user:operator", "requestedAt": "2026-09-29T21:25:00Z", "targetRunId": "job:4127"}
                            """)))
    @ApiResponse(responseCode = "404", description = "No such run")
    @ApiResponse(
            responseCode = "409",
            description =
                    "job-not-restartable: a stream run, a run that is not FAILED or STOPPED, or a job that cannot be restarted")
    @ApiResponse(responseCode = "422", description = "idempotency-key-reused")
    ResponseEntity<JobRequestResponse> requestJobRestart(
            Caller caller,
            @PathVariable String runId,
            @RequestHeader(value = IdempotencyKey.HEADER, required = false) @Nullable String idempotencyKey) {
        return accepted(requestJobRestart.execute(caller, runId, IdempotencyKey.check(idempotencyKey)));
    }

    @PostMapping(ApiPaths.V1 + "/etl/jobs/{runId}/stop")
    @Operation(
            operationId = "requestJobStop",
            summary = "Stop a running job at the next chunk (operator, Idempotency-Key)")
    @ApiResponse(
            responseCode = "202",
            description = "The request is stored; etl-batch signals the stop within 5 seconds",
            content = @Content(examples = @ExampleObject(name = "request", value = """
                            {"id": "0192f6c4-5b6c-7d7e-8f9a-0b1c2d3e4f5a", "kind": "STOP", "jobName": "RawZoneReplayJob", "parameters": {}, "status": "PENDING", "requestedBy": "user:operator", "requestedAt": "2026-09-29T21:26:00Z", "targetRunId": "job:4127"}
                            """)))
    @ApiResponse(responseCode = "404", description = "No such run")
    @ApiResponse(responseCode = "409", description = "job-not-running: the run is not STARTING or STARTED")
    @ApiResponse(responseCode = "422", description = "idempotency-key-reused")
    ResponseEntity<JobRequestResponse> requestJobStop(
            Caller caller,
            @PathVariable String runId,
            @RequestHeader(value = IdempotencyKey.HEADER, required = false) @Nullable String idempotencyKey) {
        return accepted(requestJobStop.execute(caller, runId, IdempotencyKey.check(idempotencyKey)));
    }

    private static ResponseEntity<JobRequestResponse> accepted(Submitted<JobRequest> submitted) {
        JobRequest request = submitted.value();
        return ResponseEntity.accepted()
                .location(URI.create(ApiPaths.V1 + "/etl/job-requests/" + request.id()))
                .body(JobRequestResponse.from(request));
    }
}
