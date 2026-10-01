package dev.pti.analytics.recompute.adapter.out.jdbc;

import static dev.pti.analytics.recompute.adapter.out.jdbc.JdbcRecomputeScopes.utc;

import dev.pti.analytics.recompute.application.port.BunchingRecomputeStore;
import dev.pti.analytics.recompute.domain.StoredEpisode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link BunchingRecomputeStore} on {@code insight_bus_bunching} and {@code analytics_bunching_pair_state}
 * (DOC-23 §11.3). The statements run in the transaction of the recompute item; this adapter opens none.
 */
public class JdbcBunchingRecomputeStore implements BunchingRecomputeStore {

    private static final String REACHING = """
            SELECT id, episode_start, episode_end FROM insight.insight_bus_bunching
            WHERE route_id = :r AND episode_start < :at AND (status = 'OPEN' OR episode_end >= :at)""";

    private static final String STARTING_BETWEEN = """
            SELECT id, episode_start, episode_end FROM insight.insight_bus_bunching
            WHERE route_id = :r AND episode_start >= :from AND episode_start <= :to""";

    private static final String DELETE = "DELETE FROM insight.insight_bus_bunching WHERE id = :id";

    private static final String PENDING = """
            SELECT min(first_below_at) FROM insight.analytics_bunching_pair_state WHERE route_id = :r""";

    private static final String COVERAGE = """
            SELECT count(*) AS covering, coalesce(bool_or(episode_end IS NULL), false) AS open, max(episode_end) AS latest_end
            FROM insight.insight_bus_bunching
            WHERE route_id = :r AND episode_start <= :at AND (episode_end IS NULL OR episode_end >= :at)""";

    private final JdbcClient jdbc;

    public JdbcBunchingRecomputeStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<StoredEpisode> episodesReaching(String routeId, Instant point) {
        return jdbc.sql(REACHING)
                .param("r", routeId)
                .param("at", utc(point))
                .query(JdbcBunchingRecomputeStore::episode)
                .list();
    }

    @Override
    public List<StoredEpisode> episodesStartingBetween(String routeId, Instant from, Instant to) {
        return jdbc.sql(STARTING_BETWEEN)
                .param("r", routeId)
                .param("from", utc(from))
                .param("to", utc(to))
                .query(JdbcBunchingRecomputeStore::episode)
                .list();
    }

    @Override
    public void deleteEpisode(UUID id) {
        jdbc.sql(DELETE).param("id", id).update();
    }

    @Override
    public Optional<Instant> earliestPendingPair(String routeId) {
        return jdbc.sql(PENDING)
                .param("r", routeId)
                .query((rs, n) -> Optional.ofNullable(rs.getObject(1, OffsetDateTime.class))
                        .map(OffsetDateTime::toInstant))
                .single();
    }

    @Override
    public Optional<Coverage> coverageAt(String routeId, Instant point) {
        return jdbc.sql(COVERAGE)
                .param("r", routeId)
                .param("at", utc(point))
                .query((rs, n) -> {
                    if (rs.getLong("covering") == 0) {
                        return Optional.<Coverage>empty();
                    }
                    OffsetDateTime end = rs.getObject("latest_end", OffsetDateTime.class);
                    return Optional.of(new Coverage(rs.getBoolean("open"), end == null ? null : end.toInstant()));
                })
                .single();
    }

    private static StoredEpisode episode(ResultSet rs, int row) throws SQLException {
        OffsetDateTime end = rs.getObject("episode_end", OffsetDateTime.class);
        return new StoredEpisode(
                rs.getObject("id", UUID.class),
                rs.getObject("episode_start", OffsetDateTime.class).toInstant(),
                end == null ? null : end.toInstant());
    }
}
