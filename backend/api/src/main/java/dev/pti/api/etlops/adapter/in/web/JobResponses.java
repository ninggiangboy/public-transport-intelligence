package dev.pti.api.etlops.adapter.in.web;

import dev.pti.api.etlops.domain.GrafanaLinks;
import dev.pti.api.etlops.domain.JobParameter;
import dev.pti.api.etlops.domain.JobRequest;
import dev.pti.api.etlops.domain.JobRun;
import dev.pti.api.etlops.domain.JobRunDetail;
import dev.pti.api.etlops.domain.JobSummary;
import dev.pti.api.etlops.domain.RequestRef;
import dev.pti.api.etlops.domain.StepRun;
import dev.pti.api.etlops.domain.StreamBatch;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The bodies of the job endpoints E-30…E-36 (DOC-32 §6). */
final class JobResponses {

    private JobResponses() {}

    /** One run of the list (E-30). A batch job has {@code batchIds} and {@code restartable}, a stream run {@code batchCount}. */
    record JobRunResponse(
            String runId,
            String kind,
            String name,
            String status,
            @Nullable String exitCode,
            @Nullable String exitMessage,
            @Nullable String startedAt,
            @Nullable String endedAt,
            @Nullable Long durationMs,
            long readCount,
            long writeCount,
            long skipCount,
            @Nullable Long duplicateCount,
            @Nullable Long jobExecutionId,
            @Nullable List<String> batchIds,
            @Nullable Integer batchCount,
            @Nullable Boolean restartable) {

        static JobRunResponse from(JobRun run) {
            boolean batchJob = run.isBatchJob();
            return new JobRunResponse(
                    run.runId(),
                    run.kind().name(),
                    run.name(),
                    run.status(),
                    run.exitCode(),
                    run.exitMessage(),
                    EtlParams.instant(run.startedAt()),
                    EtlParams.instant(run.endedAt()),
                    duration(run.startedAt(), run.endedAt()),
                    run.readCount(),
                    run.writeCount(),
                    run.skipCount(),
                    run.duplicateCount(),
                    run.jobExecutionId(),
                    batchJob ? run.batchIds() : null,
                    batchJob ? null : run.batchCount(),
                    run.restartable());
        }
    }

    static @Nullable Long duration(@Nullable Instant from, @Nullable Instant to) {
        return from != null && to != null ? Duration.between(from, to).toMillis() : null;
    }

    record ParameterResponse(String name, String type, String value, boolean identifying) {

        static ParameterResponse from(JobParameter parameter) {
            return new ParameterResponse(
                    parameter.name(), parameter.type(), parameter.value(), parameter.identifying());
        }
    }

    record StepResponse(
            long stepExecutionId,
            String stepName,
            String status,
            @Nullable String exitCode,
            @Nullable String exitMessage,
            @Nullable String startedAt,
            @Nullable String endedAt,
            long readCount,
            long writeCount,
            long filterCount,
            long readSkipCount,
            long processSkipCount,
            long writeSkipCount,
            long commitCount,
            long rollbackCount,
            @Nullable String batchId,
            @Nullable String executionContext) {

        static StepResponse from(StepRun step) {
            return new StepResponse(
                    step.stepExecutionId(),
                    step.stepName(),
                    step.status(),
                    step.exitCode(),
                    step.exitMessage(),
                    EtlParams.instant(step.startedAt()),
                    EtlParams.instant(step.endedAt()),
                    step.readCount(),
                    step.writeCount(),
                    step.filterCount(),
                    step.readSkipCount(),
                    step.processSkipCount(),
                    step.writeSkipCount(),
                    step.commitCount(),
                    step.rollbackCount(),
                    step.batchId(),
                    step.executionContext());
        }
    }

    record RequestResponse(String type, String id) {

        static @Nullable RequestResponse from(@Nullable RequestRef ref) {
            return ref == null ? null : new RequestResponse(ref.type(), ref.id().toString());
        }
    }

    record LinksResponse(String trace, String logs) {

        static LinksResponse from(GrafanaLinks.Links links) {
            return new LinksResponse(links.trace(), links.logs());
        }
    }

    /** One micro-batch of a stream run (E-32). */
    record StreamBatchResponse(
            String batchId,
            String status,
            String writeMode,
            String instanceId,
            Map<String, Object> offsets,
            int recordsRead,
            int recordsWritten,
            int recordsSkipped,
            int recordsDuplicate,
            @Nullable String minEventTs,
            @Nullable String maxEventTs,
            String startedAt,
            String finishedAt,
            @Nullable String errorClass,
            @Nullable String errorMessage,
            LinksResponse links) {

        static StreamBatchResponse from(StreamBatch batch, GrafanaLinks grafana) {
            return new StreamBatchResponse(
                    batch.batchId(),
                    batch.status(),
                    batch.writeMode(),
                    batch.instanceId(),
                    batch.offsets(),
                    batch.recordsRead(),
                    batch.recordsWritten(),
                    batch.recordsSkipped(),
                    batch.recordsDuplicate(),
                    EtlParams.instant(batch.minEventTs()),
                    EtlParams.instant(batch.maxEventTs()),
                    EtlParams.instant(batch.startedAt()),
                    EtlParams.instant(batch.finishedAt()),
                    batch.errorClass(),
                    batch.errorMessage(),
                    LinksResponse.from(grafana.forBatch(batch.batchId(), batch.startedAt(), batch.finishedAt())));
        }
    }

    /**
     * One run in full (E-32). A batch job has {@code parameters}, {@code steps}, {@code request} and {@code links}; a
     * stream run the counters of the list and its {@code batches}.
     */
    record JobRunDetailResponse(
            String runId,
            String kind,
            String name,
            String status,
            @Nullable String exitCode,
            @Nullable String exitMessage,
            @Nullable String startedAt,
            @Nullable String endedAt,
            @Nullable Long durationMs,
            @Nullable Long readCount,
            @Nullable Long writeCount,
            @Nullable Long skipCount,
            @Nullable Long duplicateCount,
            @Nullable Integer batchCount,
            @Nullable Long jobExecutionId,
            @Nullable Boolean restartable,
            @Nullable List<ParameterResponse> parameters,
            @Nullable List<StepResponse> steps,
            @Nullable RequestResponse request,
            @Nullable LinksResponse links,
            @Nullable List<StreamBatchResponse> batches) {

        static JobRunDetailResponse from(JobRunDetail detail, GrafanaLinks grafana) {
            JobRun run = detail.run();
            if (!run.isBatchJob()) {
                return new JobRunDetailResponse(
                        run.runId(),
                        run.kind().name(),
                        run.name(),
                        run.status(),
                        null,
                        null,
                        EtlParams.instant(run.startedAt()),
                        EtlParams.instant(run.endedAt()),
                        duration(run.startedAt(), run.endedAt()),
                        run.readCount(),
                        run.writeCount(),
                        run.skipCount(),
                        run.duplicateCount(),
                        run.batchCount(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        detail.batches().stream()
                                .map(batch -> StreamBatchResponse.from(batch, grafana))
                                .toList());
            }
            Instant started = run.startedAt();
            LinksResponse links = started != null && !run.batchIds().isEmpty()
                    ? LinksResponse.from(grafana.forBatch(run.batchIds().get(0), started, run.endedAt()))
                    : null;
            return new JobRunDetailResponse(
                    run.runId(),
                    run.kind().name(),
                    run.name(),
                    run.status(),
                    run.exitCode(),
                    run.exitMessage(),
                    EtlParams.instant(started),
                    EtlParams.instant(run.endedAt()),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    run.jobExecutionId(),
                    run.restartable(),
                    detail.parameters().stream().map(ParameterResponse::from).toList(),
                    detail.steps().stream().map(StepResponse::from).toList(),
                    RequestResponse.from(detail.request()),
                    links,
                    null);
        }
    }

    /** The timeline of E-31. */
    record JobSummaryResponse(
            String bucket, String from, String to, List<SourceSeriesResponse> stream, BatchJobsResponse batchJobs) {

        static JobSummaryResponse from(JobSummary summary) {
            return new JobSummaryResponse(
                    summary.bucket().wire(),
                    EtlParams.instant(summary.from()),
                    EtlParams.instant(summary.to()),
                    summary.stream().stream().map(SourceSeriesResponse::from).toList(),
                    new BatchJobsResponse(
                            summary.batchJobs().running(),
                            summary.batchJobs().completed(),
                            summary.batchJobs().failed(),
                            summary.batchJobs().stopped()));
        }
    }

    record SourceSeriesResponse(String source, List<PointResponse> points) {

        static SourceSeriesResponse from(JobSummary.SourceSeries series) {
            return new SourceSeriesResponse(
                    series.source(),
                    series.points().stream().map(PointResponse::from).toList());
        }
    }

    record PointResponse(
            String bucketStart,
            int batches,
            int failedBatches,
            long read,
            long written,
            long skipped,
            long duplicate,
            int p95BatchMs) {

        static PointResponse from(JobSummary.Point point) {
            return new PointResponse(
                    EtlParams.instant(point.bucketStart()),
                    point.batches(),
                    point.failedBatches(),
                    point.read(),
                    point.written(),
                    point.skipped(),
                    point.duplicate(),
                    point.p95BatchMs());
        }
    }

    record BatchJobsResponse(int running, int completed, int failed, int stopped) {}

    /** A job request, as E-33 answers and E-34 reads it. */
    record JobRequestResponse(
            String id,
            String kind,
            String jobName,
            Map<String, String> parameters,
            String status,
            String requestedBy,
            String requestedAt,
            @Nullable Long jobExecutionId,
            @Nullable String runId,
            @Nullable String targetRunId,
            @Nullable String startedAt,
            @Nullable String finishedAt,
            @Nullable String message) {

        static JobRequestResponse from(JobRequest request) {
            return new JobRequestResponse(
                    request.id().toString(),
                    request.kind().name(),
                    request.jobName(),
                    request.parameters(),
                    request.status(),
                    request.requestedBy(),
                    EtlParams.instant(request.requestedAt()),
                    request.jobExecutionId(),
                    request.runId(),
                    request.targetRunId(),
                    EtlParams.instant(request.startedAt()),
                    EtlParams.instant(request.finishedAt()),
                    request.message());
        }
    }
}
