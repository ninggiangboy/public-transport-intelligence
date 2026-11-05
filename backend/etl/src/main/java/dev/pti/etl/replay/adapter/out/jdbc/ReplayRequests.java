package dev.pti.etl.replay.adapter.out.jdbc;

import dev.pti.etl.core.domain.EtlSource;
import dev.pti.etl.replay.application.port.ReplayRequestStore;
import dev.pti.etl.replay.domain.ReplayRequest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** {@link ReplayRequestStore} on {@code ops.replay_request} (DOC-22 §6, ADR-0013). */
public class ReplayRequests implements ReplayRequestStore {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ReplayRequests(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** The oldest PENDING request; {@code SKIP LOCKED} keeps two pods from claiming the same one. */
    @Override
    public Optional<ReplayRequest> claim() {
        return Optional.ofNullable(tx.execute(status -> {
            ReplayRequest request = jdbc.query(
                    """
                    SELECT id, kind, source, from_ts, to_ts, recompute_analytics
                    FROM ops.replay_request
                    WHERE status = 'PENDING'
                    ORDER BY requested_at
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                    """,
                    rs -> rs.next()
                            ? new ReplayRequest(
                                    rs.getObject("id", UUID.class),
                                    ReplayRequest.Kind.valueOf(rs.getString("kind")),
                                    EtlSource.valueOf(rs.getString("source")),
                                    instant(rs.getTimestamp("from_ts")),
                                    instant(rs.getTimestamp("to_ts")),
                                    rs.getBoolean("recompute_analytics"))
                            : null);
            if (request != null) {
                jdbc.update(
                        "UPDATE ops.replay_request SET status = 'RUNNING', started_at = now() WHERE id = ?",
                        request.id());
            }
            return request;
        }));
    }

    private static @Nullable Instant instant(@Nullable Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** The execution now running the request: first start and restarts alike (DOC-22 §4.7). */
    @Override
    public void running(UUID id, long jobExecutionId) {
        jdbc.update("""
                UPDATE ops.replay_request
                SET status = 'RUNNING', job_execution_id = ?, finished_at = NULL, message = NULL
                WHERE id = ?
                """, jobExecutionId, id);
    }

    @Override
    public void finish(UUID id, long jobExecutionId, boolean done, String statsJson, @Nullable String message) {
        jdbc.update("""
                UPDATE ops.replay_request
                SET status = ?, job_execution_id = ?, finished_at = now(), stats = ?::jsonb, message = ?
                WHERE id = ? AND status = 'RUNNING'
                """, done ? "DONE" : "FAILED", jobExecutionId, statsJson, message, id);
    }

    @Override
    public void fail(UUID id, String message) {
        jdbc.update("""
                UPDATE ops.replay_request SET status = 'FAILED', finished_at = now(), message = ?
                WHERE id = ? AND status = 'RUNNING'
                """, message, id);
    }

    /** Requests claimed by a pod that died before the job started. */
    @Override
    public int failInterrupted(Duration olderThan) {
        return jdbc.update("""
                UPDATE ops.replay_request
                SET status = 'FAILED', finished_at = now(), message = 'The launch was interrupted; submit it again'
                WHERE status = 'RUNNING' AND job_execution_id IS NULL
                  AND started_at < now() - make_interval(secs => ?)
                """, (double) olderThan.toSeconds());
    }
}
