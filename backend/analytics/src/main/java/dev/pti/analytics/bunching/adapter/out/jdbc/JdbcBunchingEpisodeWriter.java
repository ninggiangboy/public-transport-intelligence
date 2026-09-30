package dev.pti.analytics.bunching.adapter.out.jdbc;

import static dev.pti.analytics.bunching.adapter.out.jdbc.JdbcVehicleHistoryReader.utc;

import dev.pti.analytics.bunching.application.port.BunchingEpisodeWriter;
import dev.pti.analytics.bunching.domain.BunchingEpisode;
import dev.pti.analytics.bunching.domain.CloseReason;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link BunchingEpisodeWriter} with the upsert of DOC-23 §5.7. The statement names no enrichment column, so those keep
 * their defaults on insert and the values triage wrote on update. A leading {@code SELECT} reports the status the row
 * had before, from the snapshot of the statement, so that the caller can tell a change from a repeat. It takes no row
 * lock: the route's advisory lock already keeps two runs apart, and {@code FOR UPDATE} would skip the row the upsert
 * itself updates.
 */
public class JdbcBunchingEpisodeWriter implements BunchingEpisodeWriter {

    private static final String UPSERT = """
            WITH previous AS (
              SELECT status FROM insight.insight_bus_bunching WHERE id = :id)
            INSERT INTO insight.insight_bus_bunching AS b (
              id, route_id, direction_id, vehicle_leader, vehicle_follower, trip_leader, trip_follower,
              episode_start, episode_end, status, close_reason, scheduled_headway_seconds, threshold_seconds,
              min_gap_seconds, last_gap_seconds, open_stop_id, evaluation_count, last_evaluated_at, batch_id)
            VALUES (
              :id, :routeId, :directionId, :leader, :follower, :tripLeader, :tripFollower,
              :episodeStart, :episodeEnd, :status, :closeReason, :headway, :threshold,
              :minGap, :lastGap, :openStop, :evaluationCount, :lastEvaluatedAt, :batchId)
            ON CONFLICT (id) DO UPDATE SET
              trip_leader = excluded.trip_leader, trip_follower = excluded.trip_follower,
              episode_end = excluded.episode_end, status = excluded.status, close_reason = excluded.close_reason,
              scheduled_headway_seconds = excluded.scheduled_headway_seconds,
              threshold_seconds = excluded.threshold_seconds,
              min_gap_seconds = excluded.min_gap_seconds, last_gap_seconds = excluded.last_gap_seconds,
              open_stop_id = excluded.open_stop_id, evaluation_count = excluded.evaluation_count,
              last_evaluated_at = excluded.last_evaluated_at, batch_id = excluded.batch_id, updated_at = now()
            RETURNING (SELECT status FROM previous) AS previous_status""";

    private final JdbcClient jdbc;

    public JdbcBunchingEpisodeWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Previous upsert(BunchingEpisode episode, UUID batchId) {
        CloseReason reason = episode.closeReason();
        Optional<String> previous = jdbc.sql(UPSERT)
                .param("id", episode.id())
                .param("routeId", episode.routeId())
                .param("directionId", episode.directionId(), Types.SMALLINT)
                .param("leader", episode.leader())
                .param("follower", episode.follower())
                .param("tripLeader", episode.leaderTrip())
                .param("tripFollower", episode.followerTrip())
                .param("episodeStart", utc(episode.episodeStart()))
                .param("episodeEnd", timestamp(episode.episodeEnd()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("status", episode.isOpen() ? "OPEN" : "CLOSED")
                .param("closeReason", reason == null ? null : reason.name(), Types.VARCHAR)
                .param("headway", episode.scheduledHeadwaySeconds())
                .param("threshold", episode.thresholdSeconds())
                .param("minGap", episode.minGapSeconds())
                .param("lastGap", episode.lastGapSeconds())
                .param("openStop", episode.openStopId(), Types.VARCHAR)
                .param("evaluationCount", episode.evaluationCount())
                .param("lastEvaluatedAt", utc(episode.lastEvaluatedAt()))
                .param("batchId", batchId)
                // The column is NULL when the row is new, which a row mapper may not return as a bare null.
                .query((rs, n) -> Optional.ofNullable(rs.getString("previous_status")))
                .single();
        if (previous.isEmpty()) {
            return Previous.ABSENT;
        }
        return "OPEN".equals(previous.get()) ? Previous.OPEN : Previous.CLOSED;
    }

    private static @Nullable OffsetDateTime timestamp(@Nullable Instant instant) {
        return instant == null ? null : utc(instant);
    }
}
