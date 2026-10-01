package dev.pti.api.etlops.adapter.in.web;

import dev.pti.api.etlops.adapter.in.web.JobResponses.LinksResponse;
import dev.pti.api.etlops.domain.BatchLineage;
import dev.pti.api.etlops.domain.FeedVersion;
import dev.pti.api.etlops.domain.GrafanaLinks;
import dev.pti.api.etlops.domain.RuntimeFlag;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** The bodies of the batch lineage, feed and runtime flag endpoints E-37, E-38, E-55…E-57 (DOC-32 §6, §9). */
final class OpsResponses {

    private OpsResponses() {}

    record CountsResponse(long read, long written, long skipped) {}

    record DeadLettersResponse(long total, Map<String, Long> byStatus) {}

    record DataQualityResponse(String ruleId, String tableName, long violationCount, String checkedAt) {}

    /** E-37: a micro-batch has the listener, source, instance, offsets and write mode; a step the run, job and step. */
    record BatchLineageResponse(
            String batchId,
            String origin,
            @Nullable String runId,
            @Nullable String jobName,
            @Nullable String stepName,
            @Nullable Long stepExecutionId,
            @Nullable String listenerId,
            @Nullable String source,
            @Nullable String instanceId,
            @Nullable Map<String, Object> offsets,
            @Nullable String writeMode,
            @Nullable String startedAt,
            @Nullable String endedAt,
            CountsResponse counts,
            @Nullable String replayRequestId,
            DeadLettersResponse deadLetters,
            List<DataQualityResponse> dataQuality,
            @Nullable LinksResponse links) {

        static BatchLineageResponse from(BatchLineage lineage, GrafanaLinks grafana) {
            var started = lineage.startedAt();
            LinksResponse links = started == null
                    ? null
                    : LinksResponse.from(grafana.forBatch(lineage.batchId().toString(), started, lineage.endedAt()));
            return new BatchLineageResponse(
                    lineage.batchId().toString(),
                    lineage.origin().name(),
                    lineage.runId(),
                    lineage.jobName(),
                    lineage.stepName(),
                    lineage.stepExecutionId(),
                    lineage.listenerId(),
                    lineage.source(),
                    lineage.instanceId(),
                    lineage.offsets(),
                    lineage.writeMode(),
                    EtlParams.instant(started),
                    EtlParams.instant(lineage.endedAt()),
                    new CountsResponse(
                            lineage.counts().read(),
                            lineage.counts().written(),
                            lineage.counts().skipped()),
                    Objects.toString(lineage.replayRequestId(), null),
                    new DeadLettersResponse(
                            lineage.deadLetters().total(), lineage.deadLetters().byStatus()),
                    lineage.dataQuality().stream()
                            .map(result -> new DataQualityResponse(
                                    result.ruleId(),
                                    result.tableName(),
                                    result.violationCount(),
                                    EtlParams.instant(result.checkedAt())))
                            .toList(),
                    links);
        }
    }

    record ValidationResponse(int errors, int warnings) {}

    /** A feed version (E-38). */
    record FeedVersionResponse(
            long feedVersionId,
            String feedHash,
            String status,
            @Nullable String publisherName,
            @Nullable String publisherFeedVersion,
            String agencyTimezone,
            @Nullable String validFrom,
            @Nullable String validTo,
            String loadedAt,
            @Nullable String activatedAt,
            @Nullable String runId,
            ValidationResponse validation) {

        static FeedVersionResponse from(FeedVersion feed) {
            return new FeedVersionResponse(
                    feed.feedVersionId(),
                    feed.feedHash(),
                    feed.status(),
                    feed.publisherName(),
                    feed.publisherFeedVersion(),
                    feed.agencyTimezone(),
                    Objects.toString(feed.validFrom(), null),
                    Objects.toString(feed.validTo(), null),
                    EtlParams.instant(feed.loadedAt()),
                    EtlParams.instant(feed.activatedAt()),
                    feed.runId(),
                    new ValidationResponse(feed.validationErrors(), feed.validationWarnings()));
        }
    }

    /** A runtime flag (E-55…E-57). */
    record RuntimeFlagResponse(String key, Object value, String description, String updatedBy, String updatedAt) {

        static RuntimeFlagResponse from(RuntimeFlag flag) {
            return new RuntimeFlagResponse(
                    flag.key(),
                    flag.value(),
                    flag.description(),
                    flag.updatedBy(),
                    EtlParams.instant(flag.updatedAt()));
        }
    }
}
