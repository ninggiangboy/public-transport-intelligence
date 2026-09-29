package dev.pti.etl.replay;

import dev.pti.common.json.MessageJson;
import dev.pti.etl.core.InboundMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.node.ObjectNode;

/** What a replay does to existing dead letters (DOC-22 §3.2 step 4, §4.4): close them and log why. */
public class DeadLetterReplays {

    static final String ACTOR = "system:etl-batch";

    /** Dead letters still waiting for a decision; closed ones are never touched. */
    static final String OPEN = "('NEW', 'TRIAGED', 'AUTO_REPLAY_SCHEDULED', 'PENDING_CONFIRM', 'MANUAL')";

    private final JdbcTemplate jdbc;

    public DeadLetterReplays(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * {@code DlqResolveWriter}: open dead letters of the same Kafka records as the messages a raw zone replay has
     * just written become {@code RESOLVED}.
     *
     * @return how many were resolved
     */
    public int resolve(List<InboundMessage> written, UUID batchId) {
        List<String> topics = new ArrayList<>();
        List<Integer> partitions = new ArrayList<>();
        List<Long> offsets = new ArrayList<>();
        for (InboundMessage m : written) {
            if (m.topic() != null && m.partition() != null && m.offset() != null) {
                topics.add(m.topic());
                partitions.add(m.partition());
                offsets.add(m.offset());
            }
        }
        if (topics.isEmpty()) {
            return 0;
        }
        List<UUID> ids = new ArrayList<>();
        jdbc.query(
                """
                UPDATE ops.dead_letter d
                SET status = 'RESOLVED', resolved_by = ?, resolved_at = now(), updated_at = now()
                FROM unnest(?::text[], ?::int[], ?::bigint[]) AS p(topic, part, off)
                WHERE d.kafka_topic = p.topic AND d.kafka_partition = p.part AND d.kafka_offset = p.off
                  AND d.status IN\s""" + OPEN + " RETURNING d.id",
                ps -> {
                    ps.setString(1, ACTOR);
                    ps.setArray(2, ps.getConnection().createArrayOf("text", topics.toArray()));
                    ps.setArray(3, ps.getConnection().createArrayOf("int4", partitions.toArray()));
                    ps.setArray(4, ps.getConnection().createArrayOf("int8", offsets.toArray()));
                },
                rs -> {
                    ids.add(rs.getObject(1, UUID.class));
                });
        for (UUID id : ids) {
            log(id, "RESOLVED", details(null, batchId, null));
        }
        return ids.size();
    }

    /** DLQ replay succeeded: {@code REPLAY_REQUESTED → REPLAYED}; false when the row was no longer waiting. */
    public boolean markReplayed(UUID deadLetterId, UUID replayRequestId, UUID batchId, int rowsWritten) {
        int updated = jdbc.update("""
                UPDATE ops.dead_letter
                SET status = 'REPLAYED', replay_count = replay_count + 1, last_replay_at = now(), updated_at = now()
                WHERE id = ? AND status = 'REPLAY_REQUESTED'
                """, deadLetterId);
        if (updated == 1) {
            log(deadLetterId, "REPLAYED", details(replayRequestId, batchId, rowsWritten));
        }
        return updated == 1;
    }

    private void log(UUID deadLetterId, String action, String details) {
        jdbc.update(
                "INSERT INTO ops.dlq_action_log (dead_letter_id, action, actor, details) VALUES (?, ?, ?, ?::jsonb)",
                deadLetterId,
                action,
                ACTOR,
                details);
    }

    private static String details(UUID replayRequestId, UUID batchId, Integer rows) {
        ObjectNode node = MessageJson.mapper().createObjectNode();
        if (replayRequestId != null) {
            node.put("replay_request_id", replayRequestId.toString());
        }
        node.put("batch_id", batchId.toString());
        if (rows != null) {
            node.put("rows_written", rows);
        }
        return MessageJson.mapper().writeValueAsString(node);
    }
}
