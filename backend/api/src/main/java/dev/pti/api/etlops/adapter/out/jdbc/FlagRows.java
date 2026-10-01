package dev.pti.api.etlops.adapter.out.jdbc;

import dev.pti.api.etlops.domain.RuntimeFlag;
import dev.pti.api.platform.adapter.out.jdbc.ResultSets;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Maps a row of {@code ops.runtime_flag} (the columns of {@code flag_get.sql}) for the reader and the store. */
final class FlagRows {

    private final EtlRows rows;

    FlagRows(EtlRows rows) {
        this.rows = rows;
    }

    RuntimeFlag map(ResultSet rs, int row) throws SQLException {
        return new RuntimeFlag(
                rs.getString("key"),
                rows.scalar(rs.getString("value")),
                rs.getString("description"),
                rs.getString("updated_by"),
                ResultSets.instant(rs, "updated_at"));
    }
}
