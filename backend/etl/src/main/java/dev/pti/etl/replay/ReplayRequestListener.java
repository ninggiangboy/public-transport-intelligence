package dev.pti.etl.replay;

import dev.pti.common.json.MessageJson;
import dev.pti.etl.batch.StepValues;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
            String kind = dlq ? "DLQ_RECORD" : "RAW_RANGE";
            Counter.builder("pti.replay.requests")
                    .tag("kind", kind)
                    .tag("outcome", done ? "done" : "failed")
                    .register(meters)
                    .increment();
            recordMetrics(execution, kind, dlq, stats);
        } finally {
            MDC.remove(MDC_REQUEST);
        }
    }

    /** {@code pti_replay_duration_seconds}, {@code pti_replay_records_total}, {@code pti_dlq_resolved_by_replay_total}. */
    private void recordMetrics(JobExecution execution, String kind, boolean dlq, ObjectNode stats) {
        if (stats.has("duration_ms")) {
            Timer.builder("pti.replay.duration")
                    .tag("kind", kind)
                    .register(meters)
                    .record(Duration.ofMillis(stats.get("duration_ms").asLong()));
        }
        String source = dlq
                ? dlqSource(requestId(execution))
                : execution.getJobParameters().getString("source");
        if (source == null) {
            return;
        }
        if (dlq) {
            boolean replayed = "REPLAYED".equals(stats.path("outcome").asString());
            count("pti.replay.records", source, replayed ? "written" : "skipped", 1);
            count("pti.dlq.resolved.by.replay", source, null, replayed ? 1 : 0);
            return;
        }
        count("pti.replay.records", source, "written", stats.path("written").asLong());
        count("pti.replay.records", source, "duplicate", stats.path("duplicate").asLong());
        count("pti.replay.records", source, "skipped", stats.path("skipped").asLong());
        count(
                "pti.dlq.resolved.by.replay",
                source,
                null,
                stats.path("dlq_resolved").asLong());
    }

    private void count(String name, String source, @Nullable String outcome, long amount) {
        Counter.Builder builder = Counter.builder(name).tag("source", source);
        if (outcome != null) {
            builder.tag("outcome", outcome);
        }
        builder.register(meters).increment(amount);
    }

    private @Nullable String dlqSource(UUID request) {
        return jdbc.queryForObject("""
                SELECT d.source::text FROM ops.replay_request r JOIN ops.dead_letter d ON d.id = r.dead_letter_id
                WHERE r.id = ?
                """, String.class, request);
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
