package dev.pti.etl.replay;

import dev.pti.common.error.FatalException;
import dev.pti.etl.core.EtlSource;
import dev.pti.etl.core.InboundMessage;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamReader;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The one dead letter of a {@code DLQ_RECORD} request (DOC-22 §3.2): its edited payload when there is one, the raw
 * payload otherwise, with the original Kafka position so a new failure refreshes the same row.
 */
public class DeadLetterReader implements ItemStreamReader<InboundMessage> {

    static final String DEAD_LETTER_ID = "pti.replay.deadLetterId";
    static final String DONE = "pti.replay.read";

    private final JdbcTemplate jdbc;
    private @Nullable InboundMessage message;
    private boolean read;

    public DeadLetterReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void open(ExecutionContext context) {
        var stepContext = StepSynchronizationManager.getContext();
        if (stepContext == null) {
            throw new IllegalStateException("DeadLetterReader runs inside a step");
        }
        String request = stepContext.getStepExecution().getJobParameters().getString("replayRequestId");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT d.id, d.source, d.status, d.kafka_topic, d.kafka_partition, d.kafka_offset, d.kafka_timestamp,
                       coalesce(d.edited_payload::text, d.raw_payload) AS payload
                FROM ops.replay_request r JOIN ops.dead_letter d ON d.id = r.dead_letter_id
                WHERE r.id = ?::uuid
                """, request);
        if (rows.isEmpty()) {
            throw new FatalException("Replay request " + request + " has no dead letter");
        }
        Map<String, Object> row = rows.getFirst();
        UUID id = (UUID) row.get("id");
        read = context.containsKey(DONE);
        if (!read && !"REPLAY_REQUESTED".equals(row.get("status"))) {
            throw new FatalException("Dead letter " + id + " is " + row.get("status") + ", expected REPLAY_REQUESTED");
        }
        context.putString(DEAD_LETTER_ID, id.toString());
        Timestamp kafkaTs = (Timestamp) row.get("kafka_timestamp");
        Number partition = (Number) row.get("kafka_partition");
        Number offset = (Number) row.get("kafka_offset");
        message = new InboundMessage(
                EtlSource.valueOf((String) row.get("source")),
                null,
                String.valueOf(row.get("payload")).getBytes(StandardCharsets.UTF_8),
                (String) row.get("kafka_topic"),
                partition == null ? null : partition.intValue(),
                offset == null ? null : offset.longValue(),
                kafkaTs == null ? null : kafkaTs.toInstant(),
                Map.of());
    }

    @Override
    public @Nullable InboundMessage read() {
        if (read) {
            return null;
        }
        read = true;
        return message;
    }

    @Override
    public void update(ExecutionContext context) {
        if (read) {
            context.putString(DONE, "true");
        }
    }
}
