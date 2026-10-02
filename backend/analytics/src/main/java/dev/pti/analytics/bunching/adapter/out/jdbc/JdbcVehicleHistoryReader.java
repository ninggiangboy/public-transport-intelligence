package dev.pti.analytics.bunching.adapter.out.jdbc;

import dev.pti.analytics.bunching.application.port.VehicleHistoryReader;
import dev.pti.analytics.bunching.domain.StopStatus;
import dev.pti.analytics.bunching.domain.VehiclePosition;
import dev.pti.analytics.core.domain.ServiceDates.DateRange;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link VehicleHistoryReader} on {@code dw.fact_vehicle_position}, with the queries of DOC-23 §2.2 and §5.6. They use
 * the index {@code (service_date, route_id, event_timestamp)}.
 */
public class JdbcVehicleHistoryReader implements VehicleHistoryReader {

    /**
     * One service date at a time: with {@code service_date} fixed the index gives the newest row of the route by
     * reading backwards one entry. Over a {@code BETWEEN} it would scan every row of the route in the range, a few
     * thousand per day, and this runs for each route after every micro-batch.
     */
    private static final String NEWEST = """
            SELECT event_timestamp FROM dw.fact_vehicle_position
            WHERE service_date = :serviceDate AND route_id = :routeId
            ORDER BY event_timestamp DESC
            LIMIT 1""";

    private static final String ANY = """
            SELECT EXISTS (
              SELECT 1 FROM dw.fact_vehicle_position
              WHERE service_date BETWEEN :fromDate AND :toDate AND route_id = :routeId
                AND event_timestamp > :from AND event_timestamp <= :to)""";

    private static final String POSITIONS = """
            SELECT vehicle_id, event_timestamp, service_date, trip_id, direction_id, lat, lon,
                   current_stop_sequence, current_status
            FROM dw.fact_vehicle_position
            WHERE service_date BETWEEN :fromDate AND :toDate
              AND route_id = :routeId
              AND event_timestamp > :from AND event_timestamp <= :to
            ORDER BY vehicle_id, event_timestamp""";

    private final JdbcClient jdbc;

    public JdbcVehicleHistoryReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Instant> newestEventTime(String routeId, DateRange serviceDates) {
        Optional<Instant> newest = Optional.empty();
        for (LocalDate date = serviceDates.from(); !date.isAfter(serviceDates.to()); date = date.plusDays(1)) {
            Optional<Instant> ofDate = jdbc.sql(NEWEST)
                    .param("serviceDate", date)
                    .param("routeId", routeId)
                    .query((rs, n) -> rs.getObject(1, OffsetDateTime.class).toInstant())
                    .optional();
            if (ofDate.isPresent() && (newest.isEmpty() || ofDate.get().isAfter(newest.get()))) {
                newest = ofDate;
            }
        }
        return newest;
    }

    @Override
    public boolean anyPositionIn(
            String routeId, DateRange serviceDates, Instant afterExclusive, Instant upToInclusive) {
        return Boolean.TRUE.equals(jdbc.sql(ANY)
                .param("fromDate", serviceDates.from())
                .param("toDate", serviceDates.to())
                .param("routeId", routeId)
                .param("from", utc(afterExclusive))
                .param("to", utc(upToInclusive))
                .query(Boolean.class)
                .single());
    }

    @Override
    public List<VehiclePosition> positions(
            String routeId, DateRange serviceDates, Instant afterExclusive, Instant upToInclusive) {
        return jdbc.sql(POSITIONS)
                .param("fromDate", serviceDates.from())
                .param("toDate", serviceDates.to())
                .param("routeId", routeId)
                .param("from", utc(afterExclusive))
                .param("to", utc(upToInclusive))
                .query(JdbcVehicleHistoryReader::map)
                .list();
    }

    private static VehiclePosition map(ResultSet rs, int rowNum) throws SQLException {
        return new VehiclePosition(
                rs.getString("vehicle_id"),
                rs.getObject("event_timestamp", OffsetDateTime.class).toInstant(),
                rs.getObject("service_date", LocalDate.class),
                rs.getString("trip_id"),
                rs.getInt("direction_id"),
                rs.getDouble("lat"),
                rs.getDouble("lon"),
                rs.getInt("current_stop_sequence"),
                StopStatus.valueOf(rs.getString("current_status")));
    }

    static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
