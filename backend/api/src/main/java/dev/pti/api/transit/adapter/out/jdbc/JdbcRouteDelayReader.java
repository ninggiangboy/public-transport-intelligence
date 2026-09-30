package dev.pti.api.transit.adapter.out.jdbc;

import com.github.benmanes.caffeine.cache.Cache;
import dev.pti.api.platform.adapter.out.cache.ApiCaches;
import dev.pti.api.platform.adapter.out.jdbc.QueryMetrics;
import dev.pti.api.platform.adapter.out.jdbc.SqlResources;
import dev.pti.api.transit.application.port.RouteDelayReader;
import dev.pti.api.transit.domain.BucketKey;
import dev.pti.api.transit.domain.BucketSize;
import dev.pti.api.transit.domain.DelayBucket;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The delay buckets of a route from {@code dw.fact_trip_update} (DOC-32 E-03), kept 60 seconds in the {@code
 * route-delays} cache. The bucket expression is one of three fixed fragments of SQL, chosen by the {@link
 * BucketSize}; nothing from the request is ever concatenated into the statement.
 */
@Component
public final class JdbcRouteDelayReader implements RouteDelayReader {

    private static final String QUERY = "transit/route_delays";
    private static final String TEMPLATE = SqlResources.read(QUERY);

    private static final Map<BucketSize, String> SQL = new EnumMap<>(BucketSize.class);

    static {
        SQL.put(BucketSize.HOUR, sql("date_trunc('hour', tu.scheduled_arrival, 'UTC')"));
        SQL.put(BucketSize.DAY, sql("(tu.scheduled_arrival AT TIME ZONE :tz)::date"));
        SQL.put(
                BucketSize.HOUR_OF_WEEK,
                sql("(extract(isodow FROM tu.scheduled_arrival AT TIME ZONE :tz)::int * 100"
                        + " + extract(hour FROM tu.scheduled_arrival AT TIME ZONE :tz)::int)"));
    }

    private static String sql(String bucketExpression) {
        return TEMPLATE.replace("{{bucket}}", bucketExpression);
    }

    private final JdbcClient jdbc;
    private final QueryMetrics metrics;
    private final Cache<Request, List<DelayBucket>> cache;

    public JdbcRouteDelayReader(@Qualifier("reader") JdbcClient jdbc, QueryMetrics metrics, ApiCaches caches) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.cache = caches.cache("route-delays");
    }

    @Override
    public List<DelayBucket> read(Request request) {
        return cache.get(request, this::load);
    }

    private List<DelayBucket> load(Request request) {
        ZoneId zone = request.feed().timezone();
        LocalDate fromDate = request.from().atZone(zone).toLocalDate();
        LocalDate toDate = request.to().atZone(zone).toLocalDate();
        BucketSize size = request.bucket();
        return metrics.time(
                "reader",
                QUERY,
                () -> jdbc.sql(SQL.get(size))
                        .param("tz", zone.getId())
                        .param("routeId", request.routeId())
                        .param("fromDate", fromDate)
                        .param("toDate", toDate)
                        .param("from", Rows.utc(request.from()))
                        .param("to", Rows.utc(request.to()))
                        .param("directionId", request.directionId())
                        .param("early", (int) request.tolerance().early().toSeconds())
                        .param("late", (int) request.tolerance().late().toSeconds())
                        .query((rs, row) -> bucket(rs, size))
                        .list());
    }

    private static DelayBucket bucket(ResultSet rs, BucketSize size) throws SQLException {
        return new DelayBucket(
                key(rs, size),
                rs.getBigDecimal("avg_delay_seconds"),
                rs.getInt("median_delay_seconds"),
                rs.getInt("p90_delay_seconds"),
                rs.getLong("observation_count"),
                rs.getBigDecimal("on_time_percentage"));
    }

    private static BucketKey key(ResultSet rs, BucketSize size) throws SQLException {
        return switch (size) {
            case HOUR ->
                new BucketKey.Hourly(
                        rs.getObject("bucket", OffsetDateTime.class).toInstant());
            case DAY -> new BucketKey.Daily(rs.getObject("bucket", LocalDate.class));
            case HOUR_OF_WEEK -> {
                int value = rs.getInt("bucket");
                yield new BucketKey.WeekHour(value / 100, value % 100);
            }
        };
    }
}
