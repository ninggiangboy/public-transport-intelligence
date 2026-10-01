package dev.pti.api.etlops.adapter.out.jdbc;

import dev.pti.api.etlops.domain.ReplayKind;
import dev.pti.api.etlops.domain.ReplayRequest;
import dev.pti.api.platform.adapter.out.jdbc.ResultSets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

/** Maps a row of {@code ops.replay_request} (the columns of {@code replay_get.sql}) for the reader and the store. */
final class ReplayRows {

    private final EtlRows rows;

    ReplayRows(EtlRows rows) {
        this.rows = rows;
    }

    ReplayRequest map(ResultSet rs, int row) throws SQLException {
        String stats = rs.getString("stats");
        Map<String, Object> statistics = stats == null ? null : rows.object(stats);
        return new ReplayRequest(
                rs.getObject("id", UUID.class),
                ReplayKind.valueOf(rs.getString("kind")),
                rs.getString("source"),
                ResultSets.nullableInstant(rs, "from_ts"),
                ResultSets.nullableInstant(rs, "to_ts"),
                rs.getObject("dead_letter_id", UUID.class),
                rs.getBoolean("recompute_analytics"),
                rs.getString("requested_by"),
                rs.getString("idempotency_key"),
                ResultSets.instant(rs, "requested_at"),
                rs.getString("status"),
                EtlRows.longValue(rs, "job_execution_id"),
                ResultSets.nullableInstant(rs, "started_at"),
                ResultSets.nullableInstant(rs, "finished_at"),
                statistics,
                rs.getString("message"));
    }
}
