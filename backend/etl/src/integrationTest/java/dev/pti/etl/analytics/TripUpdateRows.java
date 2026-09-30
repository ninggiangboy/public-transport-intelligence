package dev.pti.etl.analytics;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Hand-made rows of {@code dw.fact_trip_update} for one route of a test: an observed arrival of a given delay, or a row
 * that must not count (not yet observed, skipped, no delay). Every row has its own trip unless the test says
 * otherwise, because the key is {@code (service_date, trip_id, stop_sequence)}.
 */
public final class TripUpdateRows {

    private static final String HASH = "0".repeat(64);

    private final JdbcTemplate jdbc;
    private final String routeId;
    private int trips;

    public TripUpdateRows(JdbcTemplate jdbc, String routeId) {
        this.jdbc = jdbc;
        this.routeId = routeId;
    }

    /** A row to insert; the defaults are an observed {@code SCHEDULED} arrival on trip {@code t<n>}, sequence 1. */
    public final class Row {

        private LocalDate serviceDate;
        private String trip = routeId + "-t" + (++trips);
        private int sequence = 1;
        private String stop = "S1";
        private Instant scheduled;
        private Instant arrival;
        private @Nullable Integer delay;
        private boolean observed = true;
        private String relationship = "SCHEDULED";

        private Row(LocalDate serviceDate, Instant scheduled, int delay) {
            this.serviceDate = serviceDate;
            this.scheduled = scheduled;
            this.arrival = scheduled.plusSeconds(delay);
            this.delay = delay;
        }

        public Row trip(String value) {
            trip = value;
            return this;
        }

        public Row sequence(int value) {
            sequence = value;
            return this;
        }

        public Row stop(String value) {
            stop = value;
            return this;
        }

        /** The observation time, when it is not {@code scheduled + delay}. */
        public Row arrivalAt(Instant value) {
            arrival = value;
            return this;
        }

        public Row withoutDelay() {
            delay = null;
            return this;
        }

        /** A prediction of the future: the arrival is after the feed's event time, so it is not observed. */
        public Row notObserved() {
            observed = false;
            return this;
        }

        public Row skipped() {
            relationship = "SKIPPED";
            return this;
        }

        public void insert() {
            Instant event = observed ? arrival : arrival.minusSeconds(60);
            jdbc.update(
                    """
                    INSERT INTO dw.fact_trip_update (service_date, trip_id, stop_sequence, route_id, direction_id,
                      stop_id, vehicle_id, schedule_relationship, scheduled_arrival, arrival_time, delay_seconds,
                      is_observed, event_timestamp, payload_hash, batch_id)
                    VALUES (?, ?, ?, ?, 0, ?, 'v1', ?, ?, ?, ?, ?, ?, ?, ?)""",
                    Date.valueOf(serviceDate),
                    trip,
                    sequence,
                    routeId,
                    stop,
                    relationship,
                    Timestamp.from(scheduled),
                    Timestamp.from(arrival),
                    delay,
                    observed,
                    Timestamp.from(event),
                    HASH,
                    UUID.randomUUID());
        }
    }

    /** An arrival that was scheduled at {@code scheduled}, observed {@code delay} seconds later. */
    public Row arrival(LocalDate serviceDate, Instant scheduled, int delay) {
        return new Row(serviceDate, scheduled, delay);
    }

    /** Removes every row of the route. */
    public void clear() {
        jdbc.update("DELETE FROM dw.fact_trip_update WHERE route_id = ?", routeId);
    }
}
