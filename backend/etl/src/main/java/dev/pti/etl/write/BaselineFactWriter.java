package dev.pti.etl.write;

import dev.pti.etl.core.WriteSet;
import java.util.List;
import java.util.function.Function;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The baseline writer of DR-27 ({@code pti.etl.baseline.write-mode=insert}): plain {@code INSERT} into the shadow
 * tables {@code exp.exp_fact_*}, which have no unique key, so every duplicate delivery becomes a counted row. No chunk
 * rules, no registry, no placeholders, no {@code vehicle_position_latest}.
 */
public class BaselineFactWriter implements ChunkWriter {

    static final String INSERT_VP = """
            INSERT INTO exp.exp_fact_vehicle_position (service_date, vehicle_id, event_timestamp, trip_id, route_id,
                direction_id, lat, lon, bearing, speed_mps, current_stop_sequence, stop_id, current_status,
                occupancy_status, schema_version, payload_hash, batch_id)
            VALUES (:service_date, :vehicle_id, :event_timestamp, :trip_id, :route_id, :direction_id, :lat, :lon,
                :bearing, :speed_mps, :current_stop_sequence, :stop_id, :current_status, :occupancy_status,
                :schema_version, :payload_hash, :batch_id)
            """;

    static final String INSERT_TU = """
            INSERT INTO exp.exp_fact_trip_update (service_date, trip_id, stop_sequence, route_id, direction_id, stop_id,
                vehicle_id, schedule_relationship, scheduled_arrival, arrival_time, departure_time, delay_seconds,
                is_observed, event_timestamp, payload_hash, batch_id)
            VALUES (:service_date, :trip_id, :stop_sequence, :route_id, :direction_id, :stop_id, :vehicle_id,
                :schedule_relationship, :scheduled_arrival, :arrival_time, :departure_time, :delay_seconds,
                :is_observed, :event_timestamp, :payload_hash, :batch_id)
            """;

    static final String INSERT_TX = """
            INSERT INTO exp.exp_fact_ticket_sales (sale_date, transaction_id, sale_point_id, route_id, stop_id,
                ticket_type, txn_type, amount, currency, refund_of, status, is_deleted, created_at, source_updated_at,
                source_lsn, event_timestamp, payload_hash, batch_id)
            VALUES (:sale_date, :transaction_id, :sale_point_id, :route_id, :stop_id, :ticket_type, :txn_type, :amount,
                :currency, :refund_of, :status, :is_deleted, :created_at, :source_updated_at, :source_lsn,
                :event_timestamp, :payload_hash, :batch_id)
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final WriteStats stats;

    public BaselineFactWriter(NamedParameterJdbcTemplate jdbc, WriteStats stats) {
        this.jdbc = jdbc;
        this.stats = stats;
    }

    @Override
    public WriteOutcome write(List<WriteSet> items, WriteContext context) {
        if (items.isEmpty()) {
            return WriteOutcome.none();
        }
        insert(
                INSERT_VP,
                items,
                w -> w.vehiclePositions().stream()
                        .map(r -> FactChunkWriter.vehiclePosition(r, context))
                        .toList());
        insert(
                INSERT_TU,
                items,
                w -> w.tripUpdates().stream()
                        .map(r -> FactChunkWriter.tripUpdate(r, context))
                        .toList());
        insert(
                INSERT_TX,
                items,
                w -> w.ticketSales().stream()
                        .map(r -> FactChunkWriter.ticketSale(r, context))
                        .toList());
        WriteOutcome outcome = new WriteOutcome(items, 0, 0, 0, 0);
        stats.recordWrite(items.getFirst().source(), context.mode(), outcome);
        return outcome;
    }

    private void insert(String sql, List<WriteSet> items, Function<WriteSet, List<MapSqlParameterSource>> rows) {
        MapSqlParameterSource[] params =
                items.stream().flatMap(w -> rows.apply(w).stream()).toArray(MapSqlParameterSource[]::new);
        if (params.length > 0) {
            jdbc.batchUpdate(sql, params);
        }
    }
}
