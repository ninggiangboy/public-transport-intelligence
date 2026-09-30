package dev.pti.api.transit.adapter.out.jdbc;

import com.github.benmanes.caffeine.cache.Cache;
import dev.pti.api.platform.adapter.out.cache.ApiCaches;
import dev.pti.api.platform.adapter.out.jdbc.QueryMetrics;
import dev.pti.api.platform.adapter.out.jdbc.SqlResources;
import dev.pti.api.platform.domain.ActiveFeed;
import dev.pti.api.transit.application.port.ArrivalReader;
import dev.pti.api.transit.domain.ArrivalCandidate;
import dev.pti.common.gtfs.GtfsTime;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The scheduled calls at a stop around now (DOC-23 §7.4, DOC-32 E-08), kept 5 seconds in the {@code arrivals} cache
 * per stop and horizon. The query does not depend on the limit, so the limit is not part of the key; the use case
 * drops what has gone and what is skipped on every request, from rows at most five seconds old.
 */
@Component
public final class JdbcArrivalReader implements ArrivalReader {

    private static final String QUERY = "transit/arrivals";
    private static final String SQL = SqlResources.read(QUERY);
    private static final Duration LOOK_BACK = Duration.ofMinutes(30);

    private record Key(long feedVersionId, String stopId, Duration horizon) {}

    private final JdbcClient jdbc;
    private final QueryMetrics metrics;
    private final Cache<Key, List<ArrivalCandidate>> cache;

    public JdbcArrivalReader(@Qualifier("reader") JdbcClient jdbc, QueryMetrics metrics, ApiCaches caches) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.cache = caches.cache("arrivals");
    }

    @Override
    public List<ArrivalCandidate> candidates(ActiveFeed feed, String stopId, Instant now, Duration horizon) {
        return cache.get(new Key(feed.feedVersionId(), stopId, horizon), key -> load(feed, stopId, now, horizon));
    }

    private List<ArrivalCandidate> load(ActiveFeed feed, String stopId, Instant now, Duration horizon) {
        ZoneId zone = feed.timezone();
        LocalDate today = now.atZone(zone).toLocalDate();
        LocalDate yesterday = today.minusDays(1);
        Instant windowStart = now.minus(LOOK_BACK);
        Instant windowEnd = now.plus(horizon);
        return metrics.time(
                "reader",
                QUERY,
                () -> jdbc.sql(SQL)
                        .param("fv", feed.feedVersionId())
                        .param("stopId", stopId)
                        .param("tz", zone.getId())
                        .param("today", today)
                        .param("now", Rows.utc(now))
                        .param("horizonSeconds", (double) horizon.toSeconds())
                        .param("secFromYesterday", seconds(yesterday, zone, windowStart))
                        .param("secToYesterday", seconds(yesterday, zone, windowEnd))
                        .param("secFromToday", seconds(today, zone, windowStart))
                        .param("secToToday", seconds(today, zone, windowEnd))
                        .query(JdbcArrivalReader::map)
                        .list());
    }

    /** GTFS seconds of an instant on a service date: counted from noon minus 12 hours (DR-09). */
    private static int seconds(LocalDate serviceDate, ZoneId zone, Instant instant) {
        return (int) Duration.between(GtfsTime.toInstant(serviceDate, 0, zone), instant)
                .toSeconds();
    }

    private static ArrivalCandidate map(ResultSet rs, int row) throws SQLException {
        return new ArrivalCandidate(
                rs.getObject("service_date", LocalDate.class),
                rs.getString("trip_id"),
                rs.getInt("stop_sequence"),
                rs.getString("route_id"),
                rs.getInt("direction_id"),
                rs.getString("trip_headsign"),
                Rows.requiredInstant(rs, "scheduled"),
                rs.getBigDecimal("avg_delay_seconds"),
                Rows.integer(rs, "sample_count"),
                rs.getObject("is_observed", Boolean.class),
                rs.getString("schedule_relationship"),
                Rows.instant(rs, "rt_time"),
                Rows.instant(rs, "rt_event_ts"));
    }
}
