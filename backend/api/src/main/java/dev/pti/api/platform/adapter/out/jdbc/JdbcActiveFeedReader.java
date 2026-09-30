package dev.pti.api.platform.adapter.out.jdbc;

import dev.pti.api.platform.application.port.ActiveFeedReader;
import dev.pti.api.platform.domain.ActiveFeed;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The ACTIVE feed version from {@code dw.gtfs_feed_version}, read through the {@code reader} datasource. */
public final class JdbcActiveFeedReader implements ActiveFeedReader {

    private static final String QUERY = "platform/active-feed";
    private static final String SQL = SqlResources.read(QUERY);

    private final JdbcClient jdbc;
    private final QueryMetrics metrics;

    public JdbcActiveFeedReader(JdbcClient jdbc, QueryMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    @Override
    public Optional<ActiveFeed> find() {
        return metrics.time(
                "reader",
                QUERY,
                () -> jdbc.sql(SQL).query(JdbcActiveFeedReader::map).optional());
    }

    private static ActiveFeed map(ResultSet rs, int rowNum) throws SQLException {
        return new ActiveFeed(
                rs.getLong("feed_version_id"),
                rs.getString("publisher_feed_version"),
                ZoneId.of(rs.getString("agency_timezone")),
                rs.getObject("valid_from", LocalDate.class),
                rs.getObject("valid_to", LocalDate.class),
                rs.getObject("activated_at", OffsetDateTime.class).toInstant());
    }
}
