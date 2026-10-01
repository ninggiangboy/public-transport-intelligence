package dev.pti.api.etlops.adapter.out.jdbc;

import dev.pti.api.etlops.application.port.RuntimeFlagStore;
import dev.pti.api.etlops.domain.RuntimeFlag;
import dev.pti.api.platform.adapter.out.jdbc.OperatorRepository;
import dev.pti.api.platform.adapter.out.jdbc.QueryMetrics;
import dev.pti.api.platform.adapter.out.jdbc.SqlResources;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Changes {@code ops.runtime_flag} as {@code replay_operator} (DOC-32 E-57). The role can set {@code value}, {@code
 * updated_by} and {@code updated_at}; it cannot rename a flag or change its description (DOC-17 §4).
 */
@Component
@OperatorRepository
public final class JdbcRuntimeFlagStore implements RuntimeFlagStore {

    private static final String GET = "etlops/flag_get";
    private static final String UPDATE = "etlops/flag_update";
    private static final String GET_SQL = SqlResources.read(GET);
    private static final String UPDATE_SQL = SqlResources.read(UPDATE);

    private final JdbcClient jdbc;
    private final QueryMetrics metrics;
    private final EtlRows json;
    private final FlagRows rows;

    public JdbcRuntimeFlagStore(@Qualifier("operator") JdbcClient jdbc, QueryMetrics metrics, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.json = new EtlRows(mapper);
        this.rows = new FlagRows(json);
    }

    @Override
    public Optional<RuntimeFlag> find(String key) {
        return metrics.time(
                "operator",
                GET,
                () -> jdbc.sql(GET_SQL).param("key", key).query(rows::map).optional());
    }

    @Override
    public Optional<RuntimeFlag> update(String key, Object value, String updatedBy) {
        return metrics.time(
                "operator",
                UPDATE,
                () -> jdbc.sql(UPDATE_SQL)
                        .param("key", key)
                        .param("value", json.json(value))
                        .param("updatedBy", updatedBy)
                        .query(rows::map)
                        .optional());
    }
}
