package dev.pti.api.etlops.adapter.out.jdbc;

import dev.pti.api.etlops.application.port.JobRequestReader;
import dev.pti.api.etlops.domain.JobRequest;
import dev.pti.api.platform.adapter.out.jdbc.QueryMetrics;
import dev.pti.api.platform.adapter.out.jdbc.SqlResources;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** {@code ops.job_request} read as {@code api_reader} (DOC-32 E-34). */
@Component
public final class JdbcJobRequestReader implements JobRequestReader {

    private static final String QUERY = "etlops/job_request_get";
    private static final String SQL = SqlResources.read(QUERY);

    private final JdbcClient jdbc;
    private final QueryMetrics metrics;
    private final JobRequestRows rows;

    public JdbcJobRequestReader(@Qualifier("reader") JdbcClient jdbc, QueryMetrics metrics, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.rows = new JobRequestRows(new EtlRows(mapper));
    }

    @Override
    public Optional<JobRequest> find(UUID id) {
        return metrics.time(
                "reader",
                QUERY,
                () -> jdbc.sql(SQL).param("id", id).query(rows::map).optional());
    }
}
