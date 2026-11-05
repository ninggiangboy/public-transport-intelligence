package dev.pti.etl.maintenance.adapter.out.jdbc;

import dev.pti.etl.maintenance.application.PurgeTarget;
import dev.pti.etl.maintenance.application.port.ExpiredRows;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link ExpiredRows} on Postgres (DOC-18 §5): one {@code DELETE} of at most {@code limit} rows, so that a caller
 * that deletes one batch per call (and per transaction) never holds locks for long (test L-03).
 */
public final class JdbcExpiredRows implements ExpiredRows {

    private final JdbcTemplate jdbc;

    public JdbcExpiredRows(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int deleteBatch(PurgeTarget target, OffsetDateTime cutoff, int limit) {
        return jdbc.update(
                "DELETE FROM " + target.table() + " WHERE ctid IN (SELECT ctid FROM " + target.table() + " WHERE "
                        + target.condition() + " LIMIT ?)",
                cutoff,
                limit);
    }
}
