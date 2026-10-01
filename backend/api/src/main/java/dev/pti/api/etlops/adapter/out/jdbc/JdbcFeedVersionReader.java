package dev.pti.api.etlops.adapter.out.jdbc;

import dev.pti.api.etlops.application.port.FeedVersionReader;
import dev.pti.api.etlops.domain.FeedVersion;
import dev.pti.api.platform.adapter.out.jdbc.QueryMetrics;
import dev.pti.api.platform.adapter.out.jdbc.ResultSets;
import dev.pti.api.platform.adapter.out.jdbc.SqlResources;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The GTFS feed versions from {@code dw.gtfs_feed_version} (DOC-32 E-38), read as {@code api_reader}. */
@Component
public final class JdbcFeedVersionReader implements FeedVersionReader {

    private static final String QUERY = "etlops/feeds";
    private static final String SQL = SqlResources.read(QUERY);

    private final JdbcClient jdbc;
    private final QueryMetrics metrics;

    public JdbcFeedVersionReader(@Qualifier("reader") JdbcClient jdbc, QueryMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    @Override
    public List<FeedVersion> latest(int limit) {
        return metrics.time(
                "reader",
                QUERY,
                () -> jdbc.sql(SQL)
                        .param("limit", limit)
                        .query((rs, row) -> {
                            Long execution = EtlRows.longValue(rs, "job_execution_id");
                            return new FeedVersion(
                                    rs.getLong("feed_version_id"),
                                    rs.getString("feed_hash"),
                                    rs.getString("status"),
                                    rs.getString("publisher_name"),
                                    rs.getString("publisher_feed_version"),
                                    rs.getString("agency_timezone"),
                                    rs.getObject("valid_from", LocalDate.class),
                                    rs.getObject("valid_to", LocalDate.class),
                                    ResultSets.instant(rs, "loaded_at"),
                                    ResultSets.nullableInstant(rs, "activated_at"),
                                    execution != null ? "job:" + execution : null,
                                    rs.getInt("validation_errors"),
                                    rs.getInt("validation_warnings"));
                        })
                        .list());
    }
}
