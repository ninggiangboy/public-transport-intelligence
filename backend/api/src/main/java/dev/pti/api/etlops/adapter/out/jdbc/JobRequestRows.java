package dev.pti.api.etlops.adapter.out.jdbc;

import dev.pti.api.etlops.domain.JobRequest;
import dev.pti.api.etlops.domain.JobRequestKind;
import dev.pti.api.platform.adapter.out.jdbc.ResultSets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Maps a row of {@code ops.job_request} (the columns of {@code job_request_get.sql}) for the reader and the store. */
final class JobRequestRows {

    private final EtlRows rows;

    JobRequestRows(EtlRows rows) {
        this.rows = rows;
    }

    JobRequest map(ResultSet rs, int row) throws SQLException {
        Map<String, String> parameters = new LinkedHashMap<>();
        rows.object(rs.getString("job_parameters"))
                .forEach((name, value) -> parameters.put(name, String.valueOf(value)));
        return new JobRequest(
                rs.getObject("id", UUID.class),
                JobRequestKind.valueOf(rs.getString("kind")),
                rs.getString("job_name"),
                parameters,
                EtlRows.longValue(rs, "target_job_execution_id"),
                rs.getString("requested_by"),
                rs.getString("idempotency_key"),
                ResultSets.instant(rs, "requested_at"),
                rs.getString("status"),
                EtlRows.longValue(rs, "job_execution_id"),
                ResultSets.nullableInstant(rs, "started_at"),
                ResultSets.nullableInstant(rs, "finished_at"),
                rs.getString("message"));
    }
}
