package dev.pti.analytics.recompute.adapter.out.jdbc;

import dev.pti.analytics.core.domain.ServiceDates.DateRange;
import dev.pti.analytics.recompute.application.port.RecomputeScopes;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link RecomputeScopes} on the fact tables and the episode tables (DOC-23 §11.1). The reads of the facts carry a
 * {@code service_date} range so that only the partitions of the range are scanned (§2.1). The statements run in the
 * transaction of the caller, if there is one; this adapter opens none (DOC-49 §5.1).
 */
public class JdbcRecomputeScopes implements RecomputeScopes {

    private static final String POSITIONS = """
            SELECT DISTINCT route_id FROM dw.fact_vehicle_position
            WHERE service_date BETWEEN :fromDate AND :toDate
              AND event_timestamp >= :from AND event_timestamp <= :to
              AND route_id IS NOT NULL
            ORDER BY route_id""";

    private static final String TRIP_UPDATES = """
            SELECT DISTINCT route_id FROM dw.fact_trip_update
            WHERE service_date BETWEEN :fromDate AND :toDate
              AND event_timestamp >= :from AND event_timestamp <= :to
              AND route_id IS NOT NULL
            ORDER BY route_id""";

    private static final String BUNCHING_EPISODES = """
            SELECT DISTINCT route_id FROM insight.insight_bus_bunching
            WHERE episode_start <= :to AND (episode_end IS NULL OR episode_end >= :from)
            ORDER BY route_id""";

    private static final String DISRUPTION_EPISODES = """
            SELECT DISTINCT route_id FROM insight.insight_service_disruption
            WHERE episode_start <= :to AND (episode_end IS NULL OR episode_end >= :from)
            ORDER BY route_id""";

    private final JdbcClient jdbc;

    public JdbcRecomputeScopes(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<String> routesWithPositions(Instant from, Instant to, DateRange serviceDates) {
        return facts(POSITIONS, from, to, serviceDates);
    }

    @Override
    public List<String> routesWithTripUpdates(Instant from, Instant to, DateRange serviceDates) {
        return facts(TRIP_UPDATES, from, to, serviceDates);
    }

    @Override
    public List<String> routesWithBunchingEpisodes(Instant from, Instant to) {
        return episodes(BUNCHING_EPISODES, from, to);
    }

    @Override
    public List<String> routesWithDisruptionEpisodes(Instant from, Instant to) {
        return episodes(DISRUPTION_EPISODES, from, to);
    }

    private List<String> facts(String sql, Instant from, Instant to, DateRange serviceDates) {
        return jdbc.sql(sql)
                .param("fromDate", serviceDates.from())
                .param("toDate", serviceDates.to())
                .param("from", utc(from))
                .param("to", utc(to))
                .query(String.class)
                .list();
    }

    private List<String> episodes(String sql, Instant from, Instant to) {
        return jdbc.sql(sql)
                .param("from", utc(from))
                .param("to", utc(to))
                .query(String.class)
                .list();
    }

    static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
