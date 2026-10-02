package dev.pti.analytics.alert.adapter.out.jdbc;

import dev.pti.analytics.alert.application.port.AlertWriter;
import dev.pti.analytics.alert.domain.AlertDraft;
import dev.pti.analytics.alert.domain.AlertRecord;
import dev.pti.analytics.alert.domain.AlertType;
import dev.pti.common.events.Audience;
import dev.pti.common.json.MessageJson;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;

/**
 * {@link AlertWriter} with the three statements of DOC-23 §10.2. They run in the caller's transaction: this adapter
 * opens none (DOC-49 §5.1). The statements {@code RETURNING} the whole row, where DOC-23 lists a few columns, because
 * {@code alert.updated} carries the full alert (DOC-33 §5.5).
 */
public class JdbcAlertWriter implements AlertWriter {

    private static final String RETURNING = """
            RETURNING id, type, severity, audience, route_id, ref_table, ref_id, title, body, created_at,
                      resolved_at""";

    private static final String OPEN = """
            INSERT INTO ops.alert_event (id, type, severity, audience, route_id, ref_table, ref_id, title, body,
                                         dedup_key)
            VALUES (:id, :type, :severity, :audience, :routeId, :refTable, :refId, :title, CAST(:body AS jsonb),
                    :dedupKey)
            ON CONFLICT ON CONSTRAINT alert_event_dedup_uk DO NOTHING
            """ + RETURNING;

    private static final String RESOLVE = """
            UPDATE ops.alert_event
            SET resolved_at = now(), body = body || CAST(:patch AS jsonb)
            WHERE dedup_key = :dedupKey AND resolved_at IS NULL
            """ + RETURNING;

    private static final String RAISE_SEVERITY = """
            UPDATE ops.alert_event
            SET severity = 2, body = body || CAST(:patch AS jsonb)
            WHERE dedup_key = :dedupKey AND severity < 2 AND resolved_at IS NULL
            """ + RETURNING;

    /** DOC-23 §11.2: a recompute that deletes the episode closes its alert and marks it as withdrawn. */
    private static final String WITHDRAW = """
            UPDATE ops.alert_event
            SET resolved_at = coalesce(resolved_at, now()), body = body || CAST('{"withdrawn": true}' AS jsonb)
            WHERE dedup_key = :dedupKey""";

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

    private static final RowMapper<AlertRecord> ROW = JdbcAlertWriter::map;

    private final JdbcClient jdbc;

    public JdbcAlertWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AlertRecord> open(AlertDraft draft) {
        return jdbc.sql(OPEN)
                .param("id", draft.id())
                .param("type", draft.type().name())
                .param("severity", draft.severity(), Types.SMALLINT)
                .param("audience", draft.audience().name())
                .param("routeId", draft.routeId(), Types.VARCHAR)
                .param("refTable", draft.refTable())
                .param("refId", draft.refId())
                .param("title", draft.title())
                .param("body", json(draft.body()))
                .param("dedupKey", draft.dedupKey())
                .query(ROW)
                .optional();
    }

    @Override
    public Optional<AlertRecord> resolve(String dedupKey, Map<String, Object> bodyPatch) {
        return jdbc.sql(RESOLVE)
                .param("dedupKey", dedupKey)
                .param("patch", json(bodyPatch))
                .query(ROW)
                .optional();
    }

    @Override
    public Optional<AlertRecord> raiseSeverity(String dedupKey, Map<String, Object> bodyPatch) {
        return jdbc.sql(RAISE_SEVERITY)
                .param("dedupKey", dedupKey)
                .param("patch", json(bodyPatch))
                .query(ROW)
                .optional();
    }

    @Override
    public boolean withdraw(String dedupKey) {
        return jdbc.sql(WITHDRAW).param("dedupKey", dedupKey).update() > 0;
    }

    private static String json(Map<String, Object> value) {
        return MessageJson.mapper().writeValueAsString(value);
    }

    private static AlertRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new AlertRecord(
                rs.getObject("id", UUID.class),
                AlertType.valueOf(rs.getString("type")),
                rs.getInt("severity"),
                Audience.valueOf(rs.getString("audience")),
                rs.getString("route_id"),
                rs.getString("ref_table"),
                rs.getString("ref_id"),
                rs.getString("title"),
                MessageJson.mapper().readValue(rs.getString("body"), JSON_OBJECT),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                instant(rs.getObject("resolved_at", OffsetDateTime.class)));
    }

    private static @Nullable Instant instant(@Nullable OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }
}
