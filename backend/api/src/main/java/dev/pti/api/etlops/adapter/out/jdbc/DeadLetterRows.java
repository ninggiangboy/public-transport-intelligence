package dev.pti.api.etlops.adapter.out.jdbc;

import dev.pti.api.etlops.domain.DeadLetterDetail;
import dev.pti.api.etlops.domain.DeadLetterItem;
import dev.pti.api.etlops.domain.DeadLetterStatus;
import dev.pti.api.platform.adapter.out.jdbc.QueryMetrics;
import dev.pti.api.platform.adapter.out.jdbc.ResultSets;
import dev.pti.api.platform.adapter.out.jdbc.SqlResources;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reads one dead letter in full: the row, the last 100 lines of its action log and its {@code DLQ_RECORD} replays. The
 * reader serves E-42 with it on the replica, the store the read-back after a write on the primary, so that what an
 * operator just did is in the answer (DOC-31 §10.1).
 */
final class DeadLetterRows {

    private static final String GET = "etlops/dlq_get";
    private static final String ACTIONS = "etlops/dlq_get_actions";
    private static final String REPLAYS = "etlops/dlq_get_replays";
    private static final String GET_SQL = SqlResources.read(GET);
    private static final String ACTIONS_SQL = SqlResources.read(ACTIONS);
    private static final String REPLAYS_SQL = SqlResources.read(REPLAYS);

    private final JdbcClient jdbc;
    private final QueryMetrics metrics;
    private final String datasource;
    private final EtlRows rows;

    DeadLetterRows(JdbcClient jdbc, QueryMetrics metrics, String datasource, EtlRows rows) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.datasource = datasource;
        this.rows = rows;
    }

    /** The head of a dead letter as the list shows it; also the first columns of {@code dlq_get.sql}. */
    static DeadLetterItem item(ResultSet rs, boolean withPreview) throws SQLException {
        return new DeadLetterItem(
                rs.getObject("id", UUID.class),
                rs.getString("source"),
                rs.getString("stage"),
                rs.getString("rule_id"),
                rs.getString("error_class"),
                rs.getString("error_message"),
                DeadLetterStatus.valueOf(rs.getString("status")),
                rs.getString("category"),
                rs.getObject("category_confidence", BigDecimal.class),
                ResultSets.nullableInt(rs, "severity"),
                rs.getObject("severity_confidence", BigDecimal.class),
                rs.getString("business_key"),
                withPreview ? rs.getString("payload_preview") : null,
                rs.getBoolean("has_edited_payload"),
                rs.getInt("replay_count"),
                rs.getInt("auto_replay_count"),
                ResultSets.instant(rs, "created_at"),
                ResultSets.instant(rs, "updated_at"));
    }

    Optional<DeadLetterDetail> find(UUID id) {
        Optional<DeadLetterDetail> head = metrics.time(
                datasource,
                GET,
                () -> jdbc.sql(GET_SQL)
                        .param("id", id.toString())
                        .query(this::head)
                        .optional());
        if (head.isEmpty()) {
            return head;
        }
        List<DeadLetterDetail.Action> actions = metrics.time(
                datasource,
                ACTIONS,
                () -> jdbc.sql(ACTIONS_SQL)
                        .param("id", id.toString())
                        .query((rs, row) -> new DeadLetterDetail.Action(
                                ResultSets.instant(rs, "at"),
                                rs.getString("action"),
                                rs.getString("actor"),
                                rs.getObject("confidence", BigDecimal.class),
                                rows.object(rs.getString("details"))))
                        .list());
        List<DeadLetterDetail.ReplayRef> replays = metrics.time(
                datasource,
                REPLAYS,
                () -> jdbc.sql(REPLAYS_SQL)
                        .param("id", id.toString())
                        .query((rs, row) -> new DeadLetterDetail.ReplayRef(
                                rs.getObject("id", UUID.class),
                                rs.getString("status"),
                                rs.getString("requested_by"),
                                ResultSets.instant(rs, "requested_at"),
                                ResultSets.nullableInstant(rs, "finished_at")))
                        .list());
        DeadLetterDetail detail = head.get();
        return Optional.of(new DeadLetterDetail(
                detail.item(),
                detail.rawPayload(),
                detail.editedPayload(),
                detail.kafka(),
                detail.batchId(),
                detail.modelVersion(),
                detail.triagedAt(),
                detail.triageAttempts(),
                detail.lastReplayAt(),
                detail.resolvedBy(),
                detail.resolvedAt(),
                actions,
                replays,
                List.of()));
    }

    private DeadLetterDetail head(ResultSet rs, int row) throws SQLException {
        String topic = rs.getString("kafka_topic");
        DeadLetterDetail.Kafka kafka = topic == null
                ? null
                : new DeadLetterDetail.Kafka(
                        topic,
                        rs.getInt("kafka_partition"),
                        rs.getLong("kafka_offset"),
                        ResultSets.nullableInstant(rs, "kafka_timestamp"));
        String edited = rs.getString("edited_payload");
        return new DeadLetterDetail(
                item(rs, false),
                rs.getString("raw_payload"),
                edited == null ? null : rows.object(edited),
                kafka,
                rs.getObject("batch_id", UUID.class),
                rs.getString("model_version"),
                ResultSets.nullableInstant(rs, "triaged_at"),
                rs.getInt("triage_attempts"),
                ResultSets.nullableInstant(rs, "last_replay_at"),
                rs.getString("resolved_by"),
                ResultSets.nullableInstant(rs, "resolved_at"),
                List.of(),
                List.of(),
                List.of());
    }
}
