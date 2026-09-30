package dev.pti.analytics.bunching.adapter.out.jdbc;

import static dev.pti.analytics.bunching.adapter.out.jdbc.JdbcVehicleHistoryReader.utc;

import dev.pti.analytics.bunching.application.port.BunchingStateStore;
import dev.pti.analytics.bunching.domain.BunchingEpisode;
import dev.pti.analytics.bunching.domain.PairState;
import dev.pti.analytics.bunching.domain.PairStateChanges;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link BunchingStateStore} on {@code analytics_bunching_cursor}, {@code analytics_bunching_pair_state} and the open
 * rows of {@code insight_bus_bunching} (DOC-23 §4.2, §5.8). It opens no transaction: the run that calls it holds one.
 */
public class JdbcBunchingStateStore implements BunchingStateStore {

    private static final String CURSOR = "SELECT last_tick FROM insight.analytics_bunching_cursor WHERE route_id = :r";

    private static final String SAVE_CURSOR = """
            INSERT INTO insight.analytics_bunching_cursor (route_id, last_tick)
            VALUES (:r, :tick)
            ON CONFLICT (route_id) DO UPDATE SET last_tick = excluded.last_tick, updated_at = now()""";

    private static final String PAIR_STATES = """
            SELECT direction_id, vehicle_leader, vehicle_follower, trip_leader, trip_follower, consecutive_below,
                   first_below_at, pending_min_gap_seconds, pending_stop_id, open_episode_id, last_evaluated_at
            FROM insight.analytics_bunching_pair_state
            WHERE route_id = :r""";

    // Only the episodes that a pair state follows: they are what the machine continues from. The episodes are read
    // together with the states, so a run that resets the states starts those pairs again from their first evaluation.
    private static final String OPEN_EPISODES = """
            SELECT b.id, b.route_id, b.direction_id, b.vehicle_leader, b.vehicle_follower, b.trip_leader,
                   b.trip_follower, b.episode_start, b.scheduled_headway_seconds, b.threshold_seconds,
                   b.min_gap_seconds, b.last_gap_seconds, b.open_stop_id, b.evaluation_count, b.last_evaluated_at
            FROM insight.insight_bus_bunching b
            JOIN insight.analytics_bunching_pair_state s ON s.open_episode_id = b.id
            WHERE s.route_id = :r AND b.status = 'OPEN'""";

    private static final String UPSERT_STATE = """
            INSERT INTO insight.analytics_bunching_pair_state (
              route_id, direction_id, vehicle_leader, vehicle_follower, trip_leader, trip_follower, consecutive_below,
              first_below_at, pending_min_gap_seconds, pending_stop_id, open_episode_id, last_evaluated_at)
            VALUES (:r, :direction, :leader, :follower, :tripLeader, :tripFollower, :consecutive,
                    :firstBelowAt, :pendingMinGap, :pendingStop, :episode, :lastEvaluatedAt)
            ON CONFLICT (route_id, direction_id, vehicle_leader, vehicle_follower) DO UPDATE SET
              trip_leader = excluded.trip_leader, trip_follower = excluded.trip_follower,
              consecutive_below = excluded.consecutive_below, first_below_at = excluded.first_below_at,
              pending_min_gap_seconds = excluded.pending_min_gap_seconds,
              pending_stop_id = excluded.pending_stop_id, open_episode_id = excluded.open_episode_id,
              last_evaluated_at = excluded.last_evaluated_at""";

    private static final String DELETE_STATE = """
            DELETE FROM insight.analytics_bunching_pair_state
            WHERE route_id = :r AND direction_id = :direction AND vehicle_leader = :leader
              AND vehicle_follower = :follower""";

    private static final String NEEDING_TICK = """
            SELECT route_id FROM insight.analytics_bunching_pair_state
            UNION
            SELECT route_id FROM insight.insight_bus_bunching WHERE status = 'OPEN'""";

    private final JdbcClient jdbc;

    public JdbcBunchingStateStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Instant> cursor(String routeId) {
        return jdbc.sql(CURSOR)
                .param("r", routeId)
                .query((rs, n) -> rs.getObject(1, OffsetDateTime.class).toInstant())
                .optional();
    }

    @Override
    public void saveCursor(String routeId, Instant lastTick) {
        jdbc.sql(SAVE_CURSOR).param("r", routeId).param("tick", utc(lastTick)).update();
    }

    @Override
    public StoredState load(String routeId) {
        List<PairState> states = jdbc.sql(PAIR_STATES)
                .param("r", routeId)
                .query(JdbcBunchingStateStore::mapState)
                .list();
        List<BunchingEpisode> open = jdbc.sql(OPEN_EPISODES)
                .param("r", routeId)
                .query(JdbcBunchingStateStore::mapOpenEpisode)
                .list();
        return new StoredState(states, open);
    }

    @Override
    public void save(String routeId, PairStateChanges changes) {
        for (PairState state : changes.deletes()) {
            jdbc.sql(DELETE_STATE)
                    .param("r", routeId)
                    .param("direction", state.directionId(), Types.SMALLINT)
                    .param("leader", state.leader())
                    .param("follower", state.follower())
                    .update();
        }
        for (PairState state : changes.upserts()) {
            jdbc.sql(UPSERT_STATE)
                    .param("r", routeId)
                    .param("direction", state.directionId(), Types.SMALLINT)
                    .param("leader", state.leader())
                    .param("follower", state.follower())
                    .param("tripLeader", state.leaderTrip())
                    .param("tripFollower", state.followerTrip())
                    .param("consecutive", state.consecutiveBelow(), Types.SMALLINT)
                    .param("firstBelowAt", timestamp(state.firstBelowAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                    .param("pendingMinGap", state.pendingMinGapSeconds(), Types.INTEGER)
                    .param("pendingStop", state.pendingStopId(), Types.VARCHAR)
                    .param("episode", state.openEpisodeId(), Types.OTHER)
                    .param("lastEvaluatedAt", utc(state.lastEvaluatedAt()))
                    .update();
        }
    }

    @Override
    public List<String> routesNeedingTick() {
        return jdbc.sql(NEEDING_TICK).query(String.class).list();
    }

    private static @Nullable OffsetDateTime timestamp(@Nullable Instant instant) {
        return instant == null ? null : utc(instant);
    }

    private static PairState mapState(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime firstBelowAt = rs.getObject("first_below_at", OffsetDateTime.class);
        int pendingMinGap = rs.getInt("pending_min_gap_seconds");
        Integer pendingMin = rs.wasNull() ? null : pendingMinGap;
        return new PairState(
                rs.getInt("direction_id"),
                rs.getString("vehicle_leader"),
                rs.getString("vehicle_follower"),
                rs.getString("trip_leader"),
                rs.getString("trip_follower"),
                rs.getInt("consecutive_below"),
                firstBelowAt == null ? null : firstBelowAt.toInstant(),
                pendingMin,
                rs.getString("pending_stop_id"),
                rs.getObject("open_episode_id", UUID.class),
                rs.getObject("last_evaluated_at", OffsetDateTime.class).toInstant());
    }

    private static BunchingEpisode mapOpenEpisode(ResultSet rs, int rowNum) throws SQLException {
        return new BunchingEpisode(
                rs.getObject("id", UUID.class),
                rs.getString("route_id"),
                rs.getInt("direction_id"),
                rs.getString("vehicle_leader"),
                rs.getString("vehicle_follower"),
                rs.getString("trip_leader"),
                rs.getString("trip_follower"),
                rs.getObject("episode_start", OffsetDateTime.class).toInstant(),
                null,
                null,
                rs.getInt("scheduled_headway_seconds"),
                rs.getInt("threshold_seconds"),
                rs.getInt("min_gap_seconds"),
                rs.getInt("last_gap_seconds"),
                rs.getString("open_stop_id"),
                rs.getInt("evaluation_count"),
                rs.getObject("last_evaluated_at", OffsetDateTime.class).toInstant());
    }
}
