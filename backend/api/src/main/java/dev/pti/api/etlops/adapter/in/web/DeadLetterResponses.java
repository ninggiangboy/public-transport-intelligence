package dev.pti.api.etlops.adapter.in.web;

import dev.pti.api.etlops.domain.ActionLogItem;
import dev.pti.api.etlops.domain.DeadLetterDetail;
import dev.pti.api.etlops.domain.DeadLetterItem;
import dev.pti.api.etlops.domain.DeadLetterSummary;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The bodies of the dead letter endpoints E-40…E-48 (DOC-32 §7). */
final class DeadLetterResponses {

    private DeadLetterResponses() {}

    /** A row of the list (E-40) and the head of the detail (E-42); only the list has {@code payloadPreview}. */
    record DeadLetterItemResponse(
            String id,
            String source,
            String stage,
            @Nullable String ruleId,
            String errorClass,
            String errorMessage,
            String status,
            @Nullable String category,
            @Nullable BigDecimal categoryConfidence,
            @Nullable Integer severity,
            @Nullable BigDecimal severityConfidence,
            @Nullable String businessKey,
            @Nullable String payloadPreview,
            boolean hasEditedPayload,
            int replayCount,
            int autoReplayCount,
            String createdAt,
            String updatedAt) {

        static DeadLetterItemResponse from(DeadLetterItem item) {
            return new DeadLetterItemResponse(
                    item.id().toString(),
                    item.source(),
                    item.stage(),
                    item.ruleId(),
                    item.errorClass(),
                    item.errorMessage(),
                    item.status().name(),
                    item.category(),
                    item.categoryConfidence(),
                    item.severity(),
                    item.severityConfidence(),
                    item.businessKey(),
                    item.payloadPreview(),
                    item.hasEditedPayload(),
                    item.replayCount(),
                    item.autoReplayCount(),
                    EtlParams.instant(item.createdAt()),
                    EtlParams.instant(item.updatedAt()));
        }
    }

    record KafkaResponse(
            String topic,
            int partition,
            long offset,
            @Nullable String timestamp) {}

    record ActionResponse(
            String at,
            String action,
            String actor,
            @Nullable BigDecimal confidence,
            Map<String, Object> details) {}

    record ReplayRefResponse(
            String id,
            String status,
            String requestedBy,
            String requestedAt,
            @Nullable String finishedAt) {}

    /** One dead letter in full (E-42 and the answer of E-43, E-46, E-47). */
    record DeadLetterDetailResponse(
            String id,
            String source,
            String stage,
            @Nullable String ruleId,
            String errorClass,
            String errorMessage,
            String status,
            @Nullable String category,
            @Nullable BigDecimal categoryConfidence,
            @Nullable Integer severity,
            @Nullable BigDecimal severityConfidence,
            @Nullable String businessKey,
            boolean hasEditedPayload,
            int replayCount,
            int autoReplayCount,
            String createdAt,
            String updatedAt,
            String rawPayload,
            @Nullable Map<String, Object> editedPayload,
            @Nullable KafkaResponse kafka,
            String batchId,
            @Nullable String modelVersion,
            @Nullable String triagedAt,
            int triageAttempts,
            @Nullable String lastReplayAt,
            @Nullable String resolvedBy,
            @Nullable String resolvedAt,
            List<ActionResponse> actions,
            List<ReplayRefResponse> replays,
            List<String> allowedActions) {

        static DeadLetterDetailResponse from(DeadLetterDetail detail) {
            DeadLetterItem item = detail.item();
            DeadLetterDetail.Kafka kafka = detail.kafka();
            return new DeadLetterDetailResponse(
                    item.id().toString(),
                    item.source(),
                    item.stage(),
                    item.ruleId(),
                    item.errorClass(),
                    item.errorMessage(),
                    item.status().name(),
                    item.category(),
                    item.categoryConfidence(),
                    item.severity(),
                    item.severityConfidence(),
                    item.businessKey(),
                    item.hasEditedPayload(),
                    item.replayCount(),
                    item.autoReplayCount(),
                    EtlParams.instant(item.createdAt()),
                    EtlParams.instant(item.updatedAt()),
                    detail.rawPayload(),
                    detail.editedPayload(),
                    kafka == null
                            ? null
                            : new KafkaResponse(
                                    kafka.topic(),
                                    kafka.partition(),
                                    kafka.offset(),
                                    EtlParams.instant(kafka.timestamp())),
                    detail.batchId().toString(),
                    detail.modelVersion(),
                    EtlParams.instant(detail.triagedAt()),
                    detail.triageAttempts(),
                    EtlParams.instant(detail.lastReplayAt()),
                    detail.resolvedBy(),
                    EtlParams.instant(detail.resolvedAt()),
                    detail.actions().stream()
                            .map(action -> new ActionResponse(
                                    EtlParams.instant(action.at()),
                                    action.action(),
                                    action.actor(),
                                    action.confidence(),
                                    action.details()))
                            .toList(),
                    detail.replays().stream()
                            .map(replay -> new ReplayRefResponse(
                                    replay.id().toString(),
                                    replay.status(),
                                    replay.requestedBy(),
                                    EtlParams.instant(replay.requestedAt()),
                                    EtlParams.instant(replay.finishedAt())))
                            .toList(),
                    detail.allowedActions());
        }
    }

    /** The counters of E-41. */
    record DeadLetterSummaryResponse(
            long open,
            Map<String, Long> byStatus,
            Map<String, Long> openBySource,
            Map<String, Long> openBySeverity,
            long createdLastHour) {

        static DeadLetterSummaryResponse from(DeadLetterSummary summary) {
            return new DeadLetterSummaryResponse(
                    summary.open(),
                    summary.byStatus(),
                    summary.openBySource(),
                    summary.openBySeverity(),
                    summary.createdLastHour());
        }
    }

    /** A line of the action log (E-48). */
    record ActionLogResponse(
            long id,
            String deadLetterId,
            String action,
            String actor,
            @Nullable BigDecimal confidence,
            Map<String, Object> details,
            String at,
            String source,
            String status) {

        static ActionLogResponse from(ActionLogItem item) {
            return new ActionLogResponse(
                    item.id(),
                    item.deadLetterId().toString(),
                    item.action(),
                    item.actor(),
                    item.confidence(),
                    item.details(),
                    EtlParams.instant(item.at()),
                    item.source(),
                    item.status().name());
        }
    }
}
