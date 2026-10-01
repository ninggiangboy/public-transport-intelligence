package dev.pti.api.etlops.adapter.out.jdbc;

import dev.pti.api.etlops.application.port.ReplayRequestStore;
import dev.pti.api.etlops.domain.ActiveReplayConflict;
import dev.pti.api.etlops.domain.ReplayRequest;
import dev.pti.api.platform.adapter.out.jdbc.OperatorRepository;
import dev.pti.api.platform.adapter.out.jdbc.QueryMetrics;
import dev.pti.api.platform.adapter.out.jdbc.ResultSets;
import dev.pti.api.platform.adapter.out.jdbc.SqlResources;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes {@code ops.replay_request} as {@code replay_operator} (DOC-32 E-44, E-45, E-50). It only inserts: {@code
 * etl-batch} updates the row when it executes it (ADR-0013). The two partial unique indexes that allow one replay in
 * flight per source and per record are the guarantee behind the 409; the adapter recognises their violation by the
 * constraint name in the message (the driver is not a compile dependency).
 */
@Component
@OperatorRepository
public final class JdbcReplayRequestStore implements ReplayRequestStore {

    private static final String GET = "etlops/replay_get";
    private static final String BY_KEY = "etlops/replay_by_key";
    private static final String INSERT = "etlops/replay_insert";
    private static final String ACTIVE_RAW = "etlops/replay_active_raw";
    private static final String ACTIVE_RECORD = "etlops/replay_active_record";
    private static final String GET_SQL = SqlResources.read(GET);
    private static final String BY_KEY_SQL = SqlResources.read(BY_KEY);
    private static final String INSERT_SQL = SqlResources.read(INSERT);
    private static final String ACTIVE_RAW_SQL = SqlResources.read(ACTIVE_RAW);
    private static final String ACTIVE_RECORD_SQL = SqlResources.read(ACTIVE_RECORD);

    private static final Set<String> IN_FLIGHT_INDEXES =
            Set.of("replay_request_one_raw_per_source", "replay_request_one_per_record");

    private final JdbcClient jdbc;
    private final QueryMetrics metrics;
    private final ReplayRows rows;

    public JdbcReplayRequestStore(@Qualifier("operator") JdbcClient jdbc, QueryMetrics metrics, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.rows = new ReplayRows(new EtlRows(mapper));
    }

    @Override
    public Optional<ReplayRequest> findByKey(String requestedBy, String idempotencyKey) {
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
    public Optional<ReplayRequest> insert(ReplayRequest request) {
        try {
            return metrics.time(
                    "operator",
                    INSERT,
                    () -> jdbc.sql(INSERT_SQL)
                            .param("id", request.id().toString())
                            .param("kind", request.kind().name())
                            .param("source", request.source())
                            .param("fromTs", ResultSets.nullableTimestamp(request.fromTs()))
                            .param("toTs", ResultSets.nullableTimestamp(request.toTs()))
                            .param("deadLetterId", Objects.toString(request.deadLetterId(), null))
                            .param("recomputeAnalytics", request.recomputeAnalytics())
                            .param("requestedBy", request.requestedBy())
                            .param("idempotencyKey", request.idempotencyKey())
                            .param("requestedAt", ResultSets.nullableTimestamp(request.requestedAt()))
                            .query(rows::map)
                            .optional());
        } catch (DuplicateKeyException e) {
            if (isInFlightConflict(e)) {
                throw new ActiveReplayConflict(e);
            }
            throw e;
        }
    }

    /** True when the violated unique index is one of those that allow one replay in flight (DOC-15 §3). */
    static boolean isInFlightConflict(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && IN_FLIGHT_INDEXES.stream().anyMatch(message::contains)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Optional<ReplayRequest> find(UUID id) {
        return metrics.time(
                "operator",
                GET,
                () -> jdbc.sql(GET_SQL)
                        .param("id", id.toString())
                        .query(rows::map)
                        .optional());
    }

    @Override
    public Optional<UUID> activeRawReplay(String source) {
        return metrics.time(
                "operator",
                ACTIVE_RAW,
                () -> jdbc.sql(ACTIVE_RAW_SQL)
                        .param("source", source)
                        .query((rs, row) -> rs.getObject("id", UUID.class))
                        .optional());
    }

    @Override
    public Optional<UUID> activeRecordReplay(UUID deadLetterId) {
        return metrics.time(
                "operator",
                ACTIVE_RECORD,
                () -> jdbc.sql(ACTIVE_RECORD_SQL)
                        .param("deadLetterId", deadLetterId.toString())
                        .query((rs, row) -> rs.getObject("id", UUID.class))
                        .optional());
    }
}
