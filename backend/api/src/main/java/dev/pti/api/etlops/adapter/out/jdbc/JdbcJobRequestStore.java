package dev.pti.api.etlops.adapter.out.jdbc;

import dev.pti.api.etlops.application.port.JobRequestStore;
import dev.pti.api.etlops.domain.JobRequest;
import dev.pti.api.platform.adapter.out.jdbc.OperatorRepository;
import dev.pti.api.platform.adapter.out.jdbc.QueryMetrics;
import dev.pti.api.platform.adapter.out.jdbc.ResultSets;
import dev.pti.api.platform.adapter.out.jdbc.SqlResources;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes {@code ops.job_request} as {@code replay_operator} (DOC-32 E-33, E-35, E-36). It only inserts: {@code
 * etl-batch} updates the row when it executes it (ADR-0013).
 */
@Component
@OperatorRepository
public final class JdbcJobRequestStore implements JobRequestStore {

    private static final String GET = "etlops/job_request_get";
    private static final String BY_KEY = "etlops/job_request_by_key";
    private static final String INSERT = "etlops/job_request_insert";
    private static final String GET_SQL = SqlResources.read(GET);
    private static final String BY_KEY_SQL = SqlResources.read(BY_KEY);
    private static final String INSERT_SQL = SqlResources.read(INSERT);

    private final JdbcClient jdbc;
    private final QueryMetrics metrics;
    private final EtlRows json;
    private final JobRequestRows rows;

    public JdbcJobRequestStore(@Qualifier("operator") JdbcClient jdbc, QueryMetrics metrics, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.json = new EtlRows(mapper);
        this.rows = new JobRequestRows(json);
    }

    @Override
    public Optional<JobRequest> findByKey(String requestedBy, String idempotencyKey) {
        return metrics.time(
                "operator",
                BY_KEY,
                () -> jdbc.sql(BY_KEY_SQL)
                        .param("requestedBy", requestedBy)
                        .param("key", idempotencyKey)
                        .query(rows::map)
                        .optional());
    }

    @Override
    public Optional<JobRequest> insert(JobRequest request) {
        return metrics.time(
                "operator",
                INSERT,
                () -> jdbc.sql(INSERT_SQL)
                        .param("id", request.id())
                        .param("kind", request.kind().name())
                        .param("jobName", request.jobName())
                        .param("jobParameters", json.json(request.parameters()))
                        .param("targetJobExecutionId", request.targetJobExecutionId())
                        .param("requestedBy", request.requestedBy())
                        .param("idempotencyKey", request.idempotencyKey())
                        .param("requestedAt", ResultSets.nullableTimestamp(request.requestedAt()))
                        .query(rows::map)
                        .optional());
    }

    @Override
    public Optional<JobRequest> find(UUID id) {
        return metrics.time(
                "operator",
                GET,
                () -> jdbc.sql(GET_SQL).param("id", id).query(rows::map).optional());
    }
}
