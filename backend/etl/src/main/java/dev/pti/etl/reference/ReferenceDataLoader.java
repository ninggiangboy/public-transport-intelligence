package dev.pti.etl.reference;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Reads a feed version from the warehouse into a {@link ReferenceData} (DOC-21 §6.1). */
public class ReferenceDataLoader {

    private static final String STOP_TIMES_ON = """
            SELECT st.trip_id,
                   array_agg(st.stop_sequence   ORDER BY st.stop_sequence) AS seqs,
                   array_agg(st.arrival_seconds ORDER BY st.stop_sequence) AS arrs
            FROM dw.gtfs_stop_time st
            JOIN dw.gtfs_trip t ON t.feed_version_id = st.feed_version_id AND t.trip_id = st.trip_id
            WHERE st.feed_version_id = :feedVersionId
              AND t.service_id IN (SELECT dw.service_ids_on(:feedVersionId, :serviceDate))
            GROUP BY st.trip_id
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final int cachedDates;

    /** @param extraDays service dates kept besides today and yesterday ({@code pti.etl.reference.extra-days}) */
    public ReferenceDataLoader(JdbcTemplate jdbc, int extraDays) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.cachedDates = extraDays + 2;
    }

    /** The id of the ACTIVE feed version, if any. */
    public Optional<Long> activeFeedVersionId() {
        List<Long> ids = jdbc.queryForList(
                "SELECT feed_version_id FROM dw.gtfs_feed_version WHERE status = 'ACTIVE'", Long.class);
        return ids.stream().findFirst();
    }

    public ReferenceData load(long feedVersionId) {
        Map<String, Object> version = jdbc.queryForMap("""
                SELECT agency_timezone, bbox_min_lon, bbox_min_lat, bbox_max_lon, bbox_max_lat
                FROM dw.gtfs_feed_version WHERE feed_version_id = ?
                """, feedVersionId);
        BoundingBox bbox = new BoundingBox(
                number(version, "bbox_min_lon"),
                number(version, "bbox_min_lat"),
                number(version, "bbox_max_lon"),
                number(version, "bbox_max_lat"));
        Set<String> routes = new HashSet<>(jdbc.queryForList(
                "SELECT route_id FROM dw.dim_route WHERE feed_version_id = ?", String.class, feedVersionId));
        Set<String> stops = new HashSet<>(jdbc.queryForList(
                "SELECT stop_id FROM dw.dim_stop WHERE feed_version_id = ?", String.class, feedVersionId));
        Map<String, TripRef> trips = new HashMap<>();
        Map<String, String> interned = new HashMap<>();
        jdbc.query(
                "SELECT trip_id, route_id, direction_id, service_id FROM dw.gtfs_trip WHERE feed_version_id = ?",
                rs -> {
                    trips.put(
                            rs.getString(1),
                            new TripRef(
                                    interned.computeIfAbsent(rs.getString(2), k -> k),
                                    rs.getShort(3),
                                    interned.computeIfAbsent(rs.getString(4), k -> k)));
                },
                feedVersionId);
        ServiceCalendar calendar = new ServiceCalendar(
                jdbc.query("""
                        SELECT service_id, monday, tuesday, wednesday, thursday, friday, saturday, sunday,
                               start_date, end_date
                        FROM dw.gtfs_calendar WHERE feed_version_id = ?
                        """, (rs, n) -> weekly(rs), feedVersionId),
                jdbc.query(
                        "SELECT service_id, date, exception_type FROM dw.gtfs_calendar_date WHERE feed_version_id = ?",
                        (rs, n) -> new ServiceCalendar.DateChange(
                                rs.getString(1), rs.getObject(2, LocalDate.class), rs.getInt(3)),
                        feedVersionId));
        StopTimes stopTimes = new StopTimes(date -> stopTimesOn(feedVersionId, date), cachedDates);
        return new ReferenceData(
                feedVersionId,
                ZoneId.of((String) version.get("agency_timezone")),
                bbox,
                routes,
                stops,
                trips,
                calendar,
                stopTimes);
    }

    private Map<String, int[][]> stopTimesOn(long feedVersionId, LocalDate serviceDate) {
        Map<String, int[][]> result = new HashMap<>();
        named.query(
                STOP_TIMES_ON,
                new MapSqlParameterSource()
                        .addValue("feedVersionId", feedVersionId)
                        .addValue("serviceDate", serviceDate),
                rs -> {
                    result.put(rs.getString(1), new int[][] {ints(rs.getArray(2)), ints(rs.getArray(3))});
                });
        return Map.copyOf(result);
    }

    private static ServiceCalendar.Weekly weekly(ResultSet rs) throws SQLException {
        boolean[] days = new boolean[7];
        for (int i = 0; i < 7; i++) {
            days[i] = rs.getBoolean(2 + i);
        }
        return new ServiceCalendar.Weekly(
                rs.getString(1), days, rs.getObject(9, LocalDate.class), rs.getObject(10, LocalDate.class));
    }

    private static int[] ints(Array array) throws SQLException {
        Object[] values = (Object[]) array.getArray();
        int[] result = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = ((Number) values[i]).intValue();
        }
        return result;
    }

    private static double number(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (value == null) {
            throw new IllegalStateException("Feed version has no " + column + "; it was never finalized");
        }
        return ((Number) value).doubleValue();
    }
}
