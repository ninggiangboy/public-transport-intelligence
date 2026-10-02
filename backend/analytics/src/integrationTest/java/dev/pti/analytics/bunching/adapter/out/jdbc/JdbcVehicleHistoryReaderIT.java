package dev.pti.analytics.bunching.adapter.out.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.analytics.WarehouseSupport;
import dev.pti.analytics.core.domain.ServiceDates.DateRange;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** The watermark query of the bunching detector (DOC-23 §5.6) against the partitioned fact table. */
class JdbcVehicleHistoryReaderIT {

    private static final LocalDate YESTERDAY = LocalDate.parse("2026-09-28");
    private static final LocalDate TODAY = LocalDate.parse("2026-09-29");

    private final WarehouseSupport db = new WarehouseSupport();
    private final JdbcTemplate template = new JdbcTemplate(db.dataSource);
    private final JdbcVehicleHistoryReader reader = new JdbcVehicleHistoryReader(db.jdbc);
    private final String route = "HR-" + UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void cleanUp() {
        template.update("DELETE FROM dw.fact_vehicle_position WHERE route_id IN (?, ?)", route, route + "-other");
    }

    private void position(String routeId, LocalDate serviceDate, String vehicle, String at) {
        template.update("""
                INSERT INTO dw.fact_vehicle_position (service_date, vehicle_id, event_timestamp, trip_id, route_id,
                  direction_id, lat, lon, current_stop_sequence, stop_id, current_status, schema_version, payload_hash,
                  batch_id)
                VALUES (?, ?, ?, 'trip', ?, 0, 44.9, -93.2, 1, 'S1', 'IN_TRANSIT_TO', 1, 'h', ?)""", serviceDate, vehicle, Timestamp.from(Instant.parse(at)), routeId, UUID.randomUUID());
    }

    @Test
    void theNewestPositionOfTheRouteOverEveryServiceDateOfTheRange() {
        position(route, TODAY, "1", "2026-09-29T21:00:00Z");
        position(route, TODAY, "2", "2026-09-29T21:05:00Z");
        // A late trip of yesterday's service day, still running after a trip of today started.
        position(route, YESTERDAY, "3", "2026-09-29T21:07:00Z");
        position(route + "-other", TODAY, "4", "2026-09-29T22:00:00Z");

        assertThat(reader.newestEventTime(route, new DateRange(YESTERDAY, TODAY)))
                .contains(Instant.parse("2026-09-29T21:07:00Z"));
        assertThat(reader.newestEventTime(route, new DateRange(TODAY, TODAY)))
                .contains(Instant.parse("2026-09-29T21:05:00Z"));
    }

    @Test
    void aRouteWithoutPositionsHasNone() {
        position(route, YESTERDAY, "1", "2026-09-28T21:00:00Z");

        assertThat(reader.newestEventTime(route, new DateRange(TODAY, TODAY))).isEmpty();
        assertThat(reader.newestEventTime(route + "-none", new DateRange(YESTERDAY, TODAY)))
                .isEmpty();
        assertThat(reader.newestEventTime(route, new DateRange(YESTERDAY, TODAY)))
                .contains(Instant.parse("2026-09-28T21:00:00Z"));
    }
}
