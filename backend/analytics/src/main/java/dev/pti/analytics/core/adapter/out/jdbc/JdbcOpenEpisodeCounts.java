package dev.pti.analytics.core.adapter.out.jdbc;

import dev.pti.analytics.core.application.port.OpenEpisodeCounts;
import dev.pti.analytics.core.domain.Detector;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@link OpenEpisodeCounts} over the partial indexes on {@code status = 'OPEN'} (V7). */
public class JdbcOpenEpisodeCounts implements OpenEpisodeCounts {

    private static final String BUNCHING = "SELECT count(*) FROM insight.insight_bus_bunching WHERE status = 'OPEN'";

    private static final String DISRUPTION =
            "SELECT count(*) FROM insight.insight_service_disruption WHERE status = 'OPEN'";

    private final JdbcClient jdbc;

    public JdbcOpenEpisodeCounts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long count(Detector detector) {
        String sql =
                switch (detector) {
                    case BUNCHING -> BUNCHING;
                    case DISRUPTION -> DISRUPTION;
                    default -> throw new IllegalArgumentException(detector + " keeps no episodes");
                };
        return jdbc.sql(sql).query(Long.class).single();
    }
}
