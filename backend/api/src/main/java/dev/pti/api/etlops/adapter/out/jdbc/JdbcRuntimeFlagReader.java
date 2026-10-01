package dev.pti.api.etlops.adapter.out.jdbc;

import dev.pti.api.etlops.application.port.RuntimeFlagReader;
import dev.pti.api.etlops.domain.RuntimeFlag;
import dev.pti.api.platform.adapter.out.jdbc.QueryMetrics;
import dev.pti.api.platform.adapter.out.jdbc.SqlResources;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** {@code ops.runtime_flag} read as {@code api_reader} (DOC-32 E-55, E-56). */
@Component
public final class JdbcRuntimeFlagReader implements RuntimeFlagReader {

    private static final String LIST = "etlops/flags_list";
    private static final String GET = "etlops/flag_get";
    private static final String LIST_SQL = SqlResources.read(LIST);
    private static final String GET_SQL = SqlResources.read(GET);

    private final JdbcClient jdbc;
    private final QueryMetrics metrics;
    private final FlagRows rows;

    public JdbcRuntimeFlagReader(@Qualifier("reader") JdbcClient jdbc, QueryMetrics metrics, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.rows = new FlagRows(new EtlRows(mapper));
    }

    @Override
    public List<RuntimeFlag> list() {
        return metrics.time(
                "reader", LIST, () -> jdbc.sql(LIST_SQL).query(rows::map).list());
    }

    @Override
    public Optional<RuntimeFlag> find(String key) {
        return metrics.time(
                "reader",
                GET,
                () -> jdbc.sql(GET_SQL).param("key", key).query(rows::map).optional());
    }
}
