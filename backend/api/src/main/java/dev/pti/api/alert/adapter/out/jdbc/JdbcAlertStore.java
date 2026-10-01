package dev.pti.api.alert.adapter.out.jdbc;

import dev.pti.api.alert.application.port.AlertStore;
import dev.pti.api.alert.domain.Alert;
import dev.pti.api.alert.domain.NewAlert;
import dev.pti.api.platform.adapter.out.jdbc.OperatorRepository;
import dev.pti.api.platform.adapter.out.jdbc.QueryMetrics;
import dev.pti.api.platform.adapter.out.jdbc.SqlResources;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the API writes to {@code ops.alert_event}, through the {@code operator} datasource (DOC-32 E-21, E-80):
 * acknowledgement, the insert of an Alertmanager alert, and its resolution. {@code replay_operator} may insert rows,
 * and update {@code acknowledged_by}, {@code acknowledged_at} and {@code resolved_at}, and nothing else (DOC-17).
 */
@Component
@OperatorRepository
public final class JdbcAlertStore implements AlertStore {

    private static final String GET = "alert/alert_get";
    private static final String ACK = "alert/alert_ack";
    private static final String INSERT = "alert/alert_insert";
    private static final String RESOLVE = "alert/alert_resolve";
    private static final String GET_SQL = SqlResources.read(GET);
    private static final String ACK_SQL = SqlResources.read(ACK);
    private static final String INSERT_SQL = SqlResources.read(INSERT);
    private static final String RESOLVE_SQL = SqlResources.read(RESOLVE);

    private final JdbcClient jdbc;
    private final QueryMetrics metrics;
    private final JsonMapper json;

    public JdbcAlertStore(@Qualifier("operator") JdbcClient jdbc, QueryMetrics metrics, JsonMapper json) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.json = json;
    }

    @Override
    public Optional<Alert> find(UUID id) {
        return metrics.time(
                "operator",
                GET,
                () -> jdbc.sql(GET_SQL)
                        .param("id", id)
                        .query((rs, row) -> AlertRows.map(rs, json))
                        .optional());
    }

    @Override
    public Optional<Alert> acknowledge(UUID id, String actor) {
        return metrics.time(
                "operator",
                ACK,
                () -> jdbc.sql(ACK_SQL)
                        .param("id", id)
                        .param("actor", actor)
                        .query((rs, row) -> AlertRows.map(rs, json))
                        .optional());
    }

    @Override
    public Optional<Alert> insertIfAbsent(NewAlert alert) {
        return metrics.time(
                "operator",
                INSERT,
                () -> jdbc.sql(INSERT_SQL)
                        .param("id", alert.id())
                        .param("type", alert.type().name())
                        .param("severity", alert.severity())
                        .param("audience", alert.audience().name())
                        .param("routeId", alert.routeId())
                        .param("title", alert.title())
                        .param("body", json.writeValueAsString(alert.body()))
                        .param("dedupKey", alert.dedupKey())
                        .query((rs, row) -> AlertRows.map(rs, json))
                        .optional());
    }

    @Override
    public Optional<Alert> resolve(String dedupKey) {
        return metrics.time(
                "operator",
                RESOLVE,
                () -> jdbc.sql(RESOLVE_SQL)
                        .param("dedupKey", dedupKey)
                        .query((rs, row) -> AlertRows.map(rs, json))
                        .optional());
    }
}
