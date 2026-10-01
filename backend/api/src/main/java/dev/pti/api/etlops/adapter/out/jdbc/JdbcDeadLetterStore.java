package dev.pti.api.etlops.adapter.out.jdbc;

import dev.pti.api.etlops.application.port.DeadLetterStore;
import dev.pti.api.etlops.domain.DeadLetterDetail;
import dev.pti.api.etlops.domain.DeadLetterStatus;
import dev.pti.api.platform.adapter.out.jdbc.OperatorRepository;
import dev.pti.api.platform.adapter.out.jdbc.QueryMetrics;
import dev.pti.api.platform.adapter.out.jdbc.SqlResources;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Changes dead letters as {@code replay_operator} (DOC-32 §7). The role can update only {@code status}, {@code
 * edited_payload}, {@code resolved_by}, {@code resolved_at} and {@code updated_at}, and can insert into {@code
 * dlq_action_log} but not change or delete a line of it (DOC-17 §4); the grants are the second line of defence behind
 * the status rules of the use cases.
 */
@Component
@OperatorRepository
public final class JdbcDeadLetterStore implements DeadLetterStore {

    private static final String TRANSITION = "etlops/dlq_transition";
    private static final String EDIT = "etlops/dlq_edit";
    private static final String LOG = "etlops/dlq_log_insert";
    private static final String TRANSITION_SQL = SqlResources.read(TRANSITION);
    private static final String EDIT_SQL = SqlResources.read(EDIT);
    private static final String LOG_SQL = SqlResources.read(LOG);

    private final JdbcClient jdbc;
    private final QueryMetrics metrics;
    private final EtlRows json;
    private final DeadLetterRows details;

    public JdbcDeadLetterStore(@Qualifier("operator") JdbcClient jdbc, QueryMetrics metrics, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.json = new EtlRows(mapper);
        this.details = new DeadLetterRows(jdbc, metrics, "operator", json);
    }

    @Override
    public Optional<DeadLetterDetail> find(UUID id) {
        return details.find(id);
    }

    @Override
    public Optional<Changed> transition(
            UUID id, Set<DeadLetterStatus> from, DeadLetterStatus to, @Nullable String closedBy) {
        return metrics.time(
                "operator",
                TRANSITION,
                () -> jdbc.sql(TRANSITION_SQL)
                        .param("id", id.toString())
                        .param("fromStatuses", names(from))
                        .param("toStatus", to.name())
                        .param("closedBy", closedBy)
                        .query(JdbcDeadLetterStore::changed)
                        .optional());
    }

    @Override
    public Optional<Changed> saveEditedPayload(UUID id, Set<DeadLetterStatus> from, String payloadJson) {
        return metrics.time(
                "operator",
                EDIT,
                () -> jdbc.sql(EDIT_SQL)
                        .param("id", id.toString())
                        .param("fromStatuses", names(from))
                        .param("payload", payloadJson)
                        .query(JdbcDeadLetterStore::changed)
                        .optional());
    }

    @Override
    public void log(
            UUID id, String action, String actor, @Nullable BigDecimal confidence, Map<String, Object> details) {
        metrics.time(
                "operator",
                LOG,
                () -> jdbc.sql(LOG_SQL)
                        .param("id", id.toString())
                        .param("action", action)
                        .param("actor", actor)
                        .param("confidence", confidence)
                        .param("details", json.json(details))
                        .update());
    }

    private static String[] names(Set<DeadLetterStatus> statuses) {
        return statuses.stream().map(DeadLetterStatus::name).sorted().toArray(String[]::new);
    }

    private static Changed changed(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Changed(
                DeadLetterStatus.valueOf(rs.getString("previous_status")),
                rs.getString("source"),
                rs.getObject("category_confidence", BigDecimal.class));
    }
}
