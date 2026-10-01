package dev.pti.analytics.recompute.adapter.out.jdbc;

import static dev.pti.analytics.recompute.adapter.out.jdbc.JdbcRecomputeScopes.utc;

import dev.pti.analytics.disruption.domain.BaselineState;
import dev.pti.analytics.recompute.application.port.DisruptionRecomputeStore;
import dev.pti.analytics.recompute.domain.StoredEpisode;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link DisruptionRecomputeStore} on {@code analytics_baseline_snapshot} and {@code insight_service_disruption}
 * (DOC-23 §11.4). The statements run in the transaction of the recompute item; this adapter opens none.
 */
public class JdbcDisruptionRecomputeStore implements DisruptionRecomputeStore {

    private static final String LATEST_SNAPSHOT = """
            SELECT ewma_mean, ewma_var, bucket_count, snapshot_hour, consecutive_high, consecutive_low
            FROM insight.analytics_baseline_snapshot
            WHERE route_id = :r AND direction_id = :d AND snapshot_hour <= :latestHour
              AND open_episode_id IS NULL AND consecutive_high = 0
            ORDER BY snapshot_hour DESC
            LIMIT 1""";

    private static final String STARTING_FROM = """
            SELECT id, episode_start, episode_end FROM insight.insight_service_disruption
            WHERE route_id = :r AND direction_id = :d AND episode_start >= :from""";

    private static final String DELETE = "DELETE FROM insight.insight_service_disruption WHERE id = :id";

    private final JdbcClient jdbc;

    public JdbcDisruptionRecomputeStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The snapshot's own hour is the start of the replay, as the {@code last_bucket} of the state (DOC-23 §11.4). */
    @Override
    public Optional<BaselineState> latestSnapshot(String routeId, int directionId, Instant latestHour) {
        return jdbc.sql(LATEST_SNAPSHOT)
                .param("r", routeId)
                .param("d", directionId, Types.SMALLINT)
                .param("latestHour", utc(latestHour))
                .query((rs, n) -> new BaselineState(
                        rs.getDouble("ewma_mean"),
                        rs.getDouble("ewma_var"),
                        rs.getInt("bucket_count"),
                        rs.getObject("snapshot_hour", OffsetDateTime.class).toInstant(),
                        rs.getInt("consecutive_high"),
                        rs.getInt("consecutive_low"),
                        null))
                .optional();
    }

    @Override
    public List<StoredEpisode> episodesStartingFrom(String routeId, int directionId, Instant from) {
        return jdbc.sql(STARTING_FROM)
                .param("r", routeId)
                .param("d", directionId, Types.SMALLINT)
                .param("from", utc(from))
                .query((rs, n) -> {
                    OffsetDateTime end = rs.getObject("episode_end", OffsetDateTime.class);
                    return new StoredEpisode(
                            rs.getObject("id", UUID.class),
                            rs.getObject("episode_start", OffsetDateTime.class).toInstant(),
                            end == null ? null : end.toInstant());
                })
                .list();
    }

    @Override
    public void deleteEpisode(UUID id) {
        jdbc.sql(DELETE).param("id", id).update();
    }
}
