package dev.pti.etl.write;

import static dev.pti.etl.WarehouseSupport.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.etl.WarehouseSupport;
import dev.pti.etl.core.WriteSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** DR-27: the baseline writer inserts every delivery, so duplicates become countable rows in {@code exp.*}. */
class BaselineFactWriterIT {

    private static WarehouseSupport db;

    @BeforeAll
    static void start() {
        db = new WarehouseSupport();
    }

    @AfterAll
    static void stop() {
        db.close();
    }

    private static WriteContext context() {
        return new WriteContext(WarehouseSupport.batchId(), RunMode.STREAM, false, NOW);
    }

    private long count(String table, String column, String value) {
        return db.jdbc.queryForObject(
                "SELECT count(*) FROM exp." + table + " WHERE " + column + "::text = ?", Long.class, value);
    }

    @Test
    void everyDeliveryIsARowAndNothingReachesTheRealFacts() {
        BaselineFactWriter writer = new BaselineFactWriter(db.named, new WriteStats(db.meters));
        String vehicle = WarehouseSupport.unique("BL");
        String trip = WarehouseSupport.unique("BLT");
        UUID sale = UUID.randomUUID();
        WriteSet vp = WarehouseSupport.vpSet(WarehouseSupport.vp(vehicle, NOW, 44.97, WarehouseSupport.hash(vehicle)));
        WriteSet tu = WarehouseSupport.tuSet(
                NOW,
                WarehouseSupport.hash(trip),
                WarehouseSupport.tu(trip, 1, NOW, true, 0, WarehouseSupport.hash(trip + 1)),
                WarehouseSupport.tu(trip, 2, NOW, false, 60, WarehouseSupport.hash(trip + 2)));
        WriteSet tx = WarehouseSupport.txSet(WarehouseSupport.sale(
                sale, "KIOSK-BL", "SALE", "2.50", null, 7, false, NOW, "c", WarehouseSupport.hash(sale.toString())));

        List<WriteSet> poll = List.of(vp, tu, tx);
        WriteOutcome first = db.tx.execute(s -> writer.write(poll, context()));
        db.tx.executeWithoutResult(s -> writer.write(poll, context()));

        assertThat(first.writtenCount()).isEqualTo(3);
        assertThat(count("exp_fact_vehicle_position", "vehicle_id", vehicle)).isEqualTo(2);
        assertThat(count("exp_fact_trip_update", "trip_id", trip)).isEqualTo(4);
        assertThat(count("exp_fact_ticket_sales", "transaction_id", sale.toString()))
                .isEqualTo(2);
        assertThat(db.jdbc.queryForObject(
                        "SELECT count(*) FROM dw.fact_vehicle_position WHERE vehicle_id = ?", Long.class, vehicle))
                .isZero();
        assertThat(writer.write(List.of(), context()).writtenCount()).isZero();
    }
}
