package dev.pti.api.insight.adapter.out.jdbc;

import com.github.benmanes.caffeine.cache.Cache;
import dev.pti.api.insight.application.port.OtpReader;
import dev.pti.api.insight.domain.OtpDay;
import dev.pti.api.insight.domain.OtpScorecard;
import dev.pti.api.platform.adapter.out.cache.ApiCaches;
import dev.pti.api.platform.adapter.out.jdbc.QueryMetrics;
import dev.pti.api.platform.adapter.out.jdbc.SqlResources;
import dev.pti.api.platform.domain.ActiveFeed;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The OTP scorecard from {@code insight.insight_otp_scorecard} (DOC-32 E-14), through the {@code reader}. The rows of a
 * range are summed per route in the domain, and the scorecard is kept 5 minutes per set of parameters in the {@code
 * otp} cache (DOC-31 §10.3): the table changes once a day.
 */
@Component
public final class JdbcOtpReader implements OtpReader {

    private static final String QUERY = "insight/otp";
    private static final String SQL = SqlResources.read(QUERY);

    /** The cache key: everything that changes the answer. */
    private record Key(
            long feedVersionId,
            LocalDate fromDate,
            LocalDate toDate,
            List<String> routeIds,
            List<Integer> routeTypes) {}

    private final JdbcClient jdbc;
    private final QueryMetrics metrics;
    private final Cache<Key, OtpScorecard> cache;

    public JdbcOtpReader(@Qualifier("reader") JdbcClient jdbc, QueryMetrics metrics, ApiCaches caches) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.cache = caches.cache("otp");
    }

    @Override
    public OtpScorecard read(
            ActiveFeed feed, LocalDate fromDate, LocalDate toDate, List<String> routeIds, List<Integer> routeTypes) {
        Key key = new Key(feed.feedVersionId(), fromDate, toDate, List.copyOf(routeIds), List.copyOf(routeTypes));
        return cache.get(key, this::load);
    }

    private OtpScorecard load(Key key) {
        List<OtpDay> days = metrics.time(
                "reader",
                QUERY,
                () -> jdbc.sql(SQL)
                        .param("fromDate", key.fromDate())
                        .param("toDate", key.toDate())
                        .param("routeIds", key.routeIds().toArray(String[]::new))
                        .param("routeTypes", key.routeTypes().toArray(Integer[]::new))
                        .param("fv", key.feedVersionId())
                        .query(JdbcOtpReader::day)
                        .list());
        return OtpScorecard.of(key.fromDate(), key.toDate(), days);
    }

    private static OtpDay day(ResultSet rs, int row) throws SQLException {
        return new OtpDay(
                rs.getString("route_id"),
                rs.getObject("service_date", LocalDate.class),
                rs.getBigDecimal("otp_percentage"),
                rs.getInt("on_time_count"),
                rs.getInt("early_count"),
                rs.getInt("late_count"),
                rs.getInt("observation_count"),
                rs.getInt("trip_count"),
                rs.getInt("early_tolerance_seconds"),
                rs.getInt("late_tolerance_seconds"));
    }
}
