package dev.pti.api.etlops.adapter.in.web;

import dev.pti.api.etlops.domain.ReplayDetail;
import dev.pti.api.etlops.domain.ReplayEstimate;
import dev.pti.api.etlops.domain.ReplayProgress;
import dev.pti.api.etlops.domain.ReplayRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** The bodies of the replay endpoints E-50…E-53 (DOC-32 §8). */
final class ReplayResponses {

    private ReplayResponses() {}

    record ProgressResponse(
            String step,
            long readCount,
            long writeCount,
            long skipCount,
            @Nullable String updatedAt) {

        static @Nullable ProgressResponse from(@Nullable ReplayProgress progress) {
            return progress == null
                    ? null
                    : new ProgressResponse(
                            progress.step(),
                            progress.readCount(),
                            progress.writeCount(),
                            progress.skipCount(),
                            EtlParams.instant(progress.updatedAt()));
        }
    }

    /**
     * A replay (E-52, and the answer of E-44, E-45, E-50). The list (E-51) has no {@code stats} and no {@code progress};
     * a {@code DLQ_RECORD} has {@code deadLetterId} instead of {@code fromTs} and {@code toTs}.
     */
    record ReplayResponse(
            String id,
            String kind,
            String source,
            @Nullable String fromTs,
            @Nullable String toTs,
            @Nullable String deadLetterId,
            @Nullable Boolean recomputeAnalytics,
            String status,
            String requestedBy,
            String requestedAt,
            @Nullable String startedAt,
            @Nullable String finishedAt,
            @Nullable Long jobExecutionId,
            @Nullable String runId,
            @Nullable ProgressResponse progress,
            @Nullable String message,
            @Nullable Map<String, Object> stats) {

        static ReplayResponse from(ReplayRequest request, boolean full) {
            return from(request, null, full);
        }

        static ReplayResponse from(ReplayDetail detail) {
            return from(detail.request(), detail.progress(), true);
        }

        private static ReplayResponse from(ReplayRequest request, @Nullable ReplayProgress progress, boolean full) {
            boolean range = request.kind() == dev.pti.api.etlops.domain.ReplayKind.RAW_RANGE;
            Long execution = request.jobExecutionId();
            Map<String, Object> stats = request.stats();
            return new ReplayResponse(
                    request.id().toString(),
                    request.kind().name(),
                    request.source(),
                    EtlParams.instant(request.fromTs()),
                    EtlParams.instant(request.toTs()),
                    Objects.toString(request.deadLetterId(), null),
                    range ? request.recomputeAnalytics() : null,
                    request.status(),
                    request.requestedBy(),
                    EtlParams.instant(request.requestedAt()),
                    EtlParams.instant(request.startedAt()),
                    EtlParams.instant(request.finishedAt()),
                    execution,
                    execution != null ? "job:" + execution : null,
                    full ? ProgressResponse.from(progress) : null,
                    request.message(),
                    full && stats != null ? camelCase(stats) : null);
        }
    }

    /** The statistics of a replay with their keys in camelCase (DOC-32 E-52); the database keeps them as DOC-22 §4.6. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> camelCase(Map<String, Object> stats) {
        Map<String, Object> converted = new LinkedHashMap<>();
        stats.forEach((key, value) -> converted.put(
                camel(key), value instanceof Map<?, ?> map ? camelCase((Map<String, Object>) map) : value));
        return converted;
    }

    private static String camel(String snake) {
        StringBuilder builder = new StringBuilder();
        boolean upper = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                builder.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return builder.toString();
    }

    /** The estimate of E-53; without history it has no numbers. */
    record ReplayEstimateResponse(
            String source,
            String fromTs,
            String toTs,
            @Nullable Long estimatedMessages,
            @Nullable Long estimatedDurationSeconds,
            String basis,
            double coverage,
            List<String> warnings) {

        static ReplayEstimateResponse from(ReplayEstimate estimate) {
            return new ReplayEstimateResponse(
                    estimate.source(),
                    EtlParams.instant(estimate.fromTs()),
                    EtlParams.instant(estimate.toTs()),
                    estimate.estimatedMessages(),
                    estimate.estimatedDurationSeconds(),
                    estimate.basis(),
                    estimate.coverage(),
                    estimate.warnings());
        }
    }
}
