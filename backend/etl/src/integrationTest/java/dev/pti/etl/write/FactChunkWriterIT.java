package dev.pti.etl.write;

import static dev.pti.etl.WarehouseSupport.NOW;
import static dev.pti.etl.WarehouseSupport.hash;
import static dev.pti.etl.WarehouseSupport.sale;
import static dev.pti.etl.WarehouseSupport.spSet;
import static dev.pti.etl.WarehouseSupport.tu;
import static dev.pti.etl.WarehouseSupport.tuSet;
import static dev.pti.etl.WarehouseSupport.txSet;
import static dev.pti.etl.WarehouseSupport.unique;
import static dev.pti.etl.WarehouseSupport.vp;
import static dev.pti.etl.WarehouseSupport.vpSet;
import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.etl.WarehouseSupport;
import dev.pti.etl.core.TripUpdateRow;
import dev.pti.etl.core.WriteSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The upserts of DOC-14 §8 through {@link FactChunkWriter}, as {@code etl_writer}: the 24 cases of DOC-14 §8.6,
 * then the chunk rules, the dedup registry and the placeholders (DOC-19 §4.3).
 */
class FactChunkWriterIT {

    private static WarehouseSupport db;

    @BeforeAll
    static void start() {
        db = new WarehouseSupport();
    }

    @AfterAll
    static void stop() {
        db.close();
    }

    private static WriteOutcome write(boolean replay, WriteSet... items) {
        return write(db.writer(false), replay, items);
    }

    private static WriteOutcome write(FactChunkWriter writer, boolean replay, WriteSet... items) {
        WriteContext context = new WriteContext(WarehouseSupport.batchId(), RunMode.STREAM, replay, NOW);
        return db.tx.execute(s -> writer.write(List.of(items), context));
    }

    private static Map<String, Object> row(String sql, Object... args) {
        return db.jdbc.queryForMap(sql, args);
    }

    @Nested
    class VehiclePosition {

        private final String vehicle = unique("V");
        private final Instant event = NOW.minusSeconds(30);

        @Test
        void case01NewRowIsWritten() {
            assertThat(write(false, vpSet(vp(vehicle, event, 44.97, hash("a")))).writtenCount())
                    .isEqualTo(1);
        }

        @Test
        void case02IdenticalResendIsBlocked() {
            write(false, vpSet(vp(vehicle, event, 44.97, hash("a"))));
            WriteOutcome again = write(false, vpSet(vp(vehicle, event, 44.97, hash("a"))));
            assertThat(again.writtenCount()).isZero();
            assertThat(again.duplicateGuard()).isEqualTo(1);
        }

        @Test
        void case03SameKeyOtherContentIsWritten() {
            write(false, vpSet(vp(vehicle, event, 44.97, hash("a"))));
            assertThat(write(false, vpSet(vp(vehicle, event, 44.98, hash("b")))).writtenCount())
                    .isEqualTo(1);
            assertThat(row("SELECT lat FROM dw.fact_vehicle_position WHERE vehicle_id = ?", vehicle))
                    .containsEntry("lat", 44.98);
        }

        @Test
        void case04ReplayRewritesIdenticalContent() {
            write(false, vpSet(vp(vehicle, event, 44.97, hash("a"))));
            assertThat(write(true, vpSet(vp(vehicle, event, 44.97, hash("a")))).writtenCount())
                    .isEqualTo(1);
        }
    }

    @Nested
    class TripUpdate {

        private final String trip = unique("T");
        private final Instant t1 = NOW.minusSeconds(60);
        private final Instant t2 = NOW.minusSeconds(30);

        private WriteSet set(Instant event, String hash, boolean observed, int delay) {
            TripUpdateRow row = tu(trip, 5, event, observed, delay, hash);
            return tuSet(event, hash, row);
        }

        private Map<String, Object> stored() {
            return row(
                    "SELECT is_observed, delay_seconds, event_timestamp FROM dw.fact_trip_update WHERE trip_id = ?",
                    trip);
        }

        @Test
        void case05NewObservedRowIsWritten() {
            assertThat(write(false, set(t1, hash("o1"), true, 60)).writtenCount())
                    .isEqualTo(1);
        }

        @Test
        void case06NewerPredictionKeepsTheObservedValues() {
            write(false, set(t1, hash("o1"), true, 60));
            assertThat(write(false, set(t2, hash("p2"), false, 300)).writtenCount())
                    .isEqualTo(1);
            assertThat(stored()).containsEntry("is_observed", true).containsEntry("delay_seconds", 60);
        }

        @Test
        void case07OlderEventIsBlocked() {
            write(false, set(t2, hash("p2"), false, 60));
            assertThat(write(false, set(t1, hash("p1"), false, 90)).writtenCount())
                    .isZero();
        }

        @Test
        void case08SameEventSameHashIsBlocked() {
            write(false, set(t1, hash("p1"), false, 60));
            assertThat(write(false, set(t1, hash("p1"), false, 60)).writtenCount())
                    .isZero();
        }

        @Test
        void case09SameEventReplayIsWritten() {
            write(false, set(t1, hash("p1"), false, 60));
            assertThat(write(true, set(t1, hash("p1"), false, 60)).writtenCount())
                    .isEqualTo(1);
        }

        @Test
        void case10NewPredictionIsWritten() {
            assertThat(write(false, set(t1, hash("p1"), false, 60)).writtenCount())
                    .isEqualTo(1);
            assertThat(stored()).containsEntry("is_observed", false);
        }

        @Test
        void case11PredictionThenObservationBecomesObserved() {
            write(false, set(t1, hash("p1"), false, 60));
            assertThat(write(false, set(t2, hash("o2"), true, 75)).writtenCount())
                    .isEqualTo(1);
            assertThat(stored()).containsEntry("is_observed", true).containsEntry("delay_seconds", 75);
        }
    }

    @Nested
    class TicketSale {

        private final UUID id = UUID.randomUUID();
        private final String salePoint = unique("KIOSK");

        private WriteSet set(long lsn, boolean deleted, String op) {
            return txSet(sale(
                    id,
                    salePoint,
                    "SALE",
                    "2.50",
                    null,
                    lsn,
                    deleted,
                    NOW.minusSeconds(60),
                    op,
                    hash(id + "-" + lsn + deleted)));
        }

        @Test
        void case12NewRowIsWritten() {
            assertThat(write(false, set(100, false, "c")).writtenCount()).isEqualTo(1);
        }

        @Test
        void case13OlderLsnIsBlocked() {
            write(false, set(200, false, "u"));
            assertThat(write(false, set(100, false, "c")).writtenCount()).isZero();
        }

        @Test
        void case14SameLsnWithoutReplayIsBlocked() {
            write(false, set(100, false, "c"));
            assertThat(write(false, set(100, false, "c")).writtenCount()).isZero();
        }

        @Test
        void case15NewerDeleteIsWritten() {
            write(false, set(100, false, "c"));
            assertThat(write(false, set(200, true, "d")).writtenCount()).isEqualTo(1);
            assertThat(row("SELECT is_deleted FROM dw.fact_ticket_sales WHERE transaction_id = ?", id))
                    .containsEntry("is_deleted", true);
        }

        @Test
        void case16SameLsnReplayIsWritten() {
            write(false, set(100, false, "c"));
            assertThat(write(true, set(100, false, "c")).writtenCount()).isEqualTo(1);
        }
    }

    @Nested
    class Latest {

        private final String vehicle = unique("V");

        private Instant latest() {
            return db.jdbc
                    .queryForObject(
                            "SELECT event_timestamp FROM dw.vehicle_position_latest WHERE vehicle_id = ?",
                            Timestamp.class,
                            vehicle)
                    .toInstant();
        }

        @Test
        void cases17To19OnlyANewerEventMovesTheVehicle() {
            Instant t1 = NOW.minusSeconds(60);
            Instant t0 = NOW.minusSeconds(90);
            Instant t2 = NOW.minusSeconds(30);
            write(false, vpSet(vp(vehicle, t1, 44.97, hash("1"))));
            assertThat(latest()).isEqualTo(t1);
            write(true, vpSet(vp(vehicle, t0, 44.90, hash("0"))));
            assertThat(latest()).isEqualTo(t1);
            write(false, vpSet(vp(vehicle, t2, 44.99, hash("2"))));
            assertThat(latest()).isEqualTo(t2);
        }
    }

    @Nested
    class SalePoint {

        private final String salePoint = unique("KIOSK");

        private WriteSet saleAt(String salePointId) {
            UUID id = UUID.randomUUID();
            return txSet(sale(id, salePointId, "SALE", "2.50", null, 10, false, NOW, "c", hash(id.toString())));
        }

        private Map<String, Object> stored() {
            return row("SELECT source, name, source_lsn FROM dw.dim_sale_point WHERE sale_point_id = ?", salePoint);
        }

        @Test
        void cases20To24InferredPlaceholdersNeverOverrideCdc() {
            write(false, saleAt(salePoint));
            assertThat(stored()).containsEntry("source", "INFERRED");
            write(false, saleAt(salePoint));
            assertThat(db.jdbc.queryForObject(
                            "SELECT count(*) FROM dw.dim_sale_point WHERE sale_point_id = ?", Long.class, salePoint))
                    .isEqualTo(1);

            assertThat(write(false, spSet(salePoint, 50, "Main St")).writtenCount())
                    .isEqualTo(1);
            assertThat(stored()).containsEntry("source", "CDC").containsEntry("name", "Main St");

            assertThat(write(false, spSet(salePoint, 40, "Old name")).writtenCount())
                    .isZero();
            write(false, saleAt(salePoint));
            assertThat(stored()).containsEntry("source", "CDC").containsEntry("source_lsn", 50L);
        }
    }

    @Nested
    class ChunkRules {

        @Test
        void identicalMessagesInOneChunkWriteOnce() {
            String vehicle = unique("V");
            WriteSet a = vpSet(vp(vehicle, NOW, 44.97, hash(vehicle)));
            WriteSet b = vpSet(vp(vehicle, NOW, 44.97, hash(vehicle)));
            WriteOutcome outcome = write(false, a, b);
            assertThat(outcome.writtenCount()).isEqualTo(1);
            assertThat(outcome.duplicateInChunk()).isEqualTo(1);
        }

        @Test
        void aNewerTripUpdateSupersedesTheOlderOneStopByStop() {
            String trip = unique("T");
            Instant t1 = NOW.minusSeconds(60);
            Instant t2 = NOW.minusSeconds(30);
            WriteSet older = tuSet(
                    t1,
                    hash(trip + 1),
                    tu(trip, 1, t1, true, 30, hash(trip + 1)),
                    tu(trip, 2, t1, false, 30, hash(trip + 1)));
            WriteSet newer = tuSet(t2, hash(trip + 2), tu(trip, 2, t2, false, 45, hash(trip + 2)));
            WriteOutcome outcome = write(false, older, newer);
            assertThat(outcome.writtenCount()).isEqualTo(2);
            assertThat(outcome.duplicateInChunk()).isZero();
            assertThat(db.jdbc.queryForObject(
                            "SELECT delay_seconds FROM dw.fact_trip_update WHERE trip_id = ? AND stop_sequence = 2",
                            Integer.class,
                            trip))
                    .isEqualTo(45);
        }

        @Test
        void conflictingPayloadsAtTheSameEventSendTheEarlierToTheDeadLetterQueue() {
            String vehicle = unique("V");
            WriteSet earlier = vpSet(vp(vehicle, NOW, 44.97, hash(vehicle + "a")));
            WriteSet later = vpSet(vp(vehicle, NOW, 44.98, hash(vehicle + "b")));
            WriteOutcome outcome = write(false, earlier, later);
            assertThat(outcome.writtenCount()).isEqualTo(1);
            assertThat(outcome.rejected()).isEqualTo(1);
            assertThat(row(
                            "SELECT stage, rule_id FROM ops.dead_letter WHERE kafka_offset = ?",
                            earlier.origin().offset()))
                    .containsEntry("stage", "DEDUP")
                    .containsEntry("rule_id", "DQ-02");
        }

        @Test
        void refundRules() {
            UUID original = UUID.randomUUID();
            write(
                    false,
                    txSet(sale(
                            original, "KIOSK-1", "SALE", "2.50", null, 1, false, NOW, "c", hash("orig" + original))));

            WriteSet valid = refund(original, "2.50", NOW.minusSeconds(60), "c");
            WriteSet tooMuch = refund(original, "5.00", NOW.minusSeconds(60), "c");
            WriteSet recentOrphan = refund(UUID.randomUUID(), "1.00", NOW.minusSeconds(60), "c");
            WriteSet oldOrphan = refund(UUID.randomUUID(), "1.00", NOW.minusSeconds(600), "c");
            WriteSet snapshotOrphan = refund(UUID.randomUUID(), "1.00", NOW.minusSeconds(600), "r");
            WriteOutcome outcome = write(false, valid, tooMuch, recentOrphan, oldOrphan, snapshotOrphan);

            assertThat(outcome.writtenCount()).isEqualTo(3);
            assertThat(outcome.rejected()).isEqualTo(2);
            assertThat(ruleOf(tooMuch)).isEqualTo("DQ-13");
            assertThat(ruleOf(oldOrphan)).isEqualTo("DQ-12");
        }

        @Test
        void aRefundAndItsOriginalInOneChunkAreBothWritten() {
            UUID original = UUID.randomUUID();
            WriteSet sale =
                    txSet(sale(original, "KIOSK-1", "SALE", "2.50", null, 1, false, NOW, "c", hash("o" + original)));
            WriteOutcome outcome = write(false, sale, refund(original, "2.50", NOW.minusSeconds(600), "c"));
            assertThat(outcome.writtenCount()).isEqualTo(2);
        }

        private WriteSet refund(UUID of, String amount, Instant createdAt, String op) {
            UUID id = UUID.randomUUID();
            return txSet(sale(id, "KIOSK-1", "REFUND", amount, of, 5, false, createdAt, op, hash("r" + id)));
        }

        private String ruleOf(WriteSet item) {
            return db.jdbc.queryForObject(
                    "SELECT rule_id FROM ops.dead_letter WHERE kafka_offset = ? AND kafka_topic = ?",
                    String.class,
                    item.origin().offset(),
                    item.origin().topic());
        }
    }

    @Nested
    class Registry {

        @Test
        void aResentMessageIsDroppedByTheRegistryButWrittenOnReplay() {
            FactChunkWriter writer = db.writer(true);
            String vehicle = unique("V");
            VehiclePositionRowFactory f = () -> vpSet(vp(vehicle, NOW, 44.97, hash("reg" + vehicle)));
            assertThat(write(writer, false, f.get()).writtenCount()).isEqualTo(1);

            WriteOutcome resent = write(writer, false, f.get());
            assertThat(resent.writtenCount()).isZero();
            assertThat(resent.duplicateRegistry()).isEqualTo(1);

            WriteOutcome replayed = write(writer, true, f.get());
            assertThat(replayed.writtenCount()).isEqualTo(1);
            assertThat(db.jdbc.queryForObject(
                            "SELECT count(*) FROM ops.dedup_registry WHERE payload_hash = ?",
                            Long.class,
                            hash("reg" + vehicle)))
                    .isEqualTo(1);
        }

        @Test
        void aRolledBackChunkRegistersNothing() {
            FactChunkWriter writer = db.writer(true);
            String vehicle = unique("V");
            WriteSet item = vpSet(vp(vehicle, NOW, 44.97, hash("rb" + vehicle)));
            WriteContext context = new WriteContext(WarehouseSupport.batchId(), RunMode.STREAM, false, NOW);
            db.tx.executeWithoutResult(s -> {
                writer.write(List.of(item), context);
                s.setRollbackOnly();
            });
            assertThat(write(writer, false, item).writtenCount()).isEqualTo(1);
        }
    }

    @Test
    void unknownVehiclesGetARealtimePlaceholder() {
        String vehicle = unique("V");
        write(false, vpSet(vp(vehicle, NOW, 44.97, hash("ph" + vehicle))));
        assertThat(row("SELECT source FROM dw.dim_vehicle WHERE vehicle_id = ?", vehicle))
                .containsEntry("source", "REALTIME");
    }

    @FunctionalInterface
    private interface VehiclePositionRowFactory {
        WriteSet get();
    }
}
