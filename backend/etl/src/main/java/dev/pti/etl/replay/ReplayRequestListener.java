package dev.pti.etl.replay;

import dev.pti.common.json.MessageJson;
import dev.pti.etl.batch.StepValues;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.node.ObjectNode;

/**
 * Keeps {@code replay_request} in step with the job running it (DOC-22 §4.6, §4.7): {@code RUNNING} with the current
 * execution at start (a restart included), then {@code DONE} or {@code FAILED} with the stats.
 */
public class ReplayRequestListener implements JobExecutionListener {

    static final String MDC_REQUEST = "replay_request_id";

    private final ReplayRequests requests;
    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;

    public ReplayRequestListener(ReplayRequests requests, JdbcTemplate jdbc, MeterRegistry meters) {
        this.requests = requests;
        this.jdbc = jdbc;
        this.meters = meters;
    }

    @Override
    public void beforeJob(JobExecution execution) {
        UUID id = requestId(execution);
        MDC.put(MDC_REQUEST, id.toString());
        requests.running(id, execution.getId());
    }

    @Override
    public void afterJob(JobExecution execution) {
        try {
            UUID id = requestId(execution);
            boolean done = execution.getStatus() == BatchStatus.COMPLETED;
            boolean dlq = execution.getJobInstance().getJobName().equals("DlqReplayJob");
            ObjectNode stats = dlq ? dlqStats(id) : rawStats(execution);
            if (execution.getStartTime() != null && execution.getEndTime() != null) {
                stats.put(
                        "duration_ms",
                        Duration.between(execution.getStartTime(), execution.getEndTime())
                                .toMillis());
            }
            String description = execution.getExitStatus().getExitDescription();
            String message = done
                    ? null
                    : description == null || description.isBlank()
                            ? execution.getExitStatus().getExitCode()
                            : description.lines().findFirst().orElse(description);
            requests.finish(
                    id, execution.getId(), done, MessageJson.mapper().writeValueAsString(stats), truncate(message));
            Counter.builder("pti.replay.requests")
                    .tag("kind", dlq ? "DLQ_RECORD" : "RAW_RANGE")
                    .tag("outcome", done ? "done" : "failed")
                    .register(meters)
                    .increment();
        } finally {
            MDC.remove(MDC_REQUEST);
        }
    }

    private static String truncate(String message) {
        return message == null || message.length() <= 1000 ? message : message.substring(0, 999) + "…";
    }

    private ObjectNode dlqStats(UUID request) {
        ObjectNode stats = MessageJson.mapper().createObjectNode();
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT d.status, d.stage, d.rule_id FROM ops.replay_request r
                JOIN ops.dead_letter d ON d.id = r.dead_letter_id WHERE r.id = ?
                """, request);
        boolean replayed = "REPLAYED".equals(row.get("status"));
        stats.put("outcome", replayed ? "REPLAYED" : "FAILED_AGAIN");
        if (!replayed) {
            stats.put("stage", String.valueOf(row.get("stage")));
            if (row.get("rule_id") != null) {
                stats.put("rule_id", String.valueOf(row.get("rule_id")));
            }
        }
        return stats;
    }

    private static ObjectNode rawStats(JobExecution execution) {
        ObjectNode stats = MessageJson.mapper().createObjectNode();
        stats.put("objects", execution.getExecutionContext().getLong(ListObjectsTasklet.OBJECT_COUNT, 0L));
        for (StepExecution step : execution.getStepExecutions()) {
            if (!step.getStepName().equals("replayRecords")) {
                continue;
            }
            ExecutionContext c = step.getExecutionContext();
            stats.put("lines_read", c.getLong(RawZoneReader.LINES_READ, 0L));
            stats.put("filtered", c.getLong(RawZoneReader.FILTERED, 0L));
            stats.put("processed", step.getReadCount());
            stats.put("written", step.getWriteCount());
            stats.put("duplicate", c.getLong(RawZoneReader.DUPLICATE, 0L));
            stats.put("skipped", step.getSkipCount() + StepValues.rejected(step));
            stats.put("dlq_inserted", c.getLong(StepValues.DLQ_INSERTED, 0L));
            stats.put("dlq_updated", c.getLong(StepValues.DLQ_UPDATED, 0L));
            stats.put("dlq_resolved", c.getLong(ReplayChunkWriter.RESOLVED, 0L));
            String min = c.getString(ReplayChunkWriter.MIN_EVENT_TS, "");
            String max = c.getString(ReplayChunkWriter.MAX_EVENT_TS, "");
            if (!min.isEmpty()) {
                stats.put("min_event_ts", min);
                stats.put("max_event_ts", max);
            }
        }
        stats.put("analytics_recomputed", false);
        return stats;
    }

    private static UUID requestId(JobExecution execution) {
        return UUID.fromString(String.valueOf(execution.getJobParameters().getString("replayRequestId")));
    }
}
