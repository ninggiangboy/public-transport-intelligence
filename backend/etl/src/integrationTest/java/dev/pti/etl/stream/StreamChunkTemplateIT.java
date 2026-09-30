package dev.pti.etl.stream;

import static dev.pti.etl.WarehouseSupport.NOW;
import static dev.pti.etl.WarehouseSupport.hash;
import static dev.pti.etl.WarehouseSupport.unique;
import static dev.pti.etl.WarehouseSupport.vp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.common.error.DeserializationException;
import dev.pti.common.error.ErrorClassifier;
import dev.pti.common.error.TransientInfraException;
import dev.pti.common.time.BusinessClock;
import dev.pti.etl.WarehouseSupport;
import dev.pti.etl.core.EtlSource;
import dev.pti.etl.core.InboundMessage;
import dev.pti.etl.core.MessageProcessor;
import dev.pti.etl.core.VehiclePositionRow;
import dev.pti.etl.core.WriteSet;
import dev.pti.etl.fault.ConfigurableFaultInjector;
import dev.pti.etl.fault.FaultAction;
import dev.pti.etl.fault.FaultPoint;
import dev.pti.etl.reference.ReferenceDataHolder;
import dev.pti.etl.rules.RuleContext;
import dev.pti.etl.write.WriteMode;
import dev.pti.etl.write.WriteStats;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** DOC-19 §6 and §12 in streaming mode, against the migrated warehouse as {@code etl_writer}. */
class StreamChunkTemplateIT {

    private static WarehouseSupport db;
    private static final ConfigurableFaultInjector FAULTS = new ConfigurableFaultInjector(code -> {
        throw new IllegalStateException("halt " + code);
    });
    private static final List<Object> EVENTS = new ArrayList<>();

    @BeforeAll
    static void start() {
        db = new WarehouseSupport();
    }

    @AfterAll
    static void stop() {
        db.close();
    }

    @AfterEach
    void reset() {
        FAULTS.disarmAll();
        EVENTS.clear();
    }

    private static StreamChunkTemplate template(boolean failBatch) {
        return new StreamChunkTemplate(
                db.tx,
                db.deadLetters,
                new StreamBatchLog(db.named),
                new ErrorClassifier(),
                FAULTS,
                EVENTS::add,
                new BusinessClock(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ZERO),
                new ReferenceDataHolder(),
                new WriteStats(db.meters),
                db.meters,
                failBatch);
    }

    /** One message per value: "bad" does not parse, "poison" breaks a CHECK constraint, anything else is valid. */
    private static final class TestProcessor implements MessageProcessor {
        @Override
        public EtlSource source() {
            return EtlSource.GTFS_RT_VEHICLE_POSITION;
        }

        @Override
        public WriteSet process(InboundMessage message, RuleContext context) {
            String value = new String(message.value(), StandardCharsets.UTF_8);
            if (value.equals("bad")) {
                throw new DeserializationException("Unexpected character", null);
            }
            double lat = value.equals("poison") ? 95.0 : 44.97;
            VehiclePositionRow row = vp(message.key(), NOW, lat, hash(message.key() + value));
            return WriteSet.vehiclePosition(message, row.payloadHash(), row.vehicleId() + "|" + NOW, row);
        }

        @Override
        public @Nullable String businessKey(InboundMessage message) {
            return message.key();
        }
    }

    private static List<InboundMessage> poll(String prefix, int size, Map<Integer, String> special, long firstOffset) {
        List<InboundMessage> messages = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            messages.add(new InboundMessage(
                    EtlSource.GTFS_RT_VEHICLE_POSITION,
                    prefix + "-" + i,
                    special.getOrDefault(i, "ok").getBytes(StandardCharsets.UTF_8),
                    "gtfs.vehicle_positions",
                    7,
                    firstOffset + i,
                    NOW.minusSeconds(1),
                    Map.of()));
        }
        return messages;
    }

    private static StreamChunkRequest request(List<InboundMessage> messages, boolean replay) {
        return new StreamChunkRequest(
                WarehouseSupport.batchId(),
                EtlSource.GTFS_RT_VEHICLE_POSITION,
                "gtfs-rt-vehicle-position",
                "pti-etl-gtfs-rt",
                "test",
                messages,
                replay);
    }

    private static StreamChunkResult run(List<InboundMessage> messages) {
        return template(false).execute(request(messages, false), new TestProcessor(), db.writer(true));
    }

    private static long facts(String prefix) {
        return db.jdbc.queryForObject(
                "SELECT count(*) FROM dw.fact_vehicle_position WHERE vehicle_id LIKE ?", Long.class, prefix + "-%");
    }

    private static List<Map<String, Object>> deadLetters(String prefix) {
        return db.jdbc.queryForList(
                "SELECT stage, error_class, rule_id, business_key, batch_id, replay_count, status FROM ops.dead_letter"
                        + " WHERE business_key LIKE ?",
                prefix + "-%");
    }

    private static Map<String, Object> batchRow(UUID batchId) {
        return db.jdbc.queryForMap("SELECT * FROM ops.etl_stream_batch WHERE batch_id = ?", batchId);
    }

    private static long nextOffsets() {
        return System.nanoTime() & 0xFFFFFFFFFFL;
    }

    @Test
    void b01OneMalformedMessageGoesToTheDeadLetterQueue() {
        String prefix = unique("B01");
        StreamChunkResult result = run(poll(prefix, 500, Map.of(36, "bad"), nextOffsets()));

        assertThat(result.written()).isEqualTo(499);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.writeMode()).isEqualTo(WriteMode.BATCH);
        assertThat(facts(prefix)).isEqualTo(499);
        assertThat(deadLetters(prefix)).singleElement().satisfies(d -> {
            assertThat(d).containsEntry("stage", "DESERIALIZE").containsEntry("business_key", prefix + "-36");
            assertThat(d.get("batch_id")).isEqualTo(result.batchId());
        });
        assertThat(batchRow(result.batchId()))
                .containsEntry("status", "COMPLETED_WITH_SKIPS")
                .containsEntry("write_mode", "BATCH")
                .containsEntry("records_read", 500)
                .containsEntry("records_written", 499)
                .containsEntry("records_skipped", 1);
        assertThat(EVENTS).singleElement().isInstanceOfSatisfying(MicroBatchCommitted.class, event -> {
            assertThat(event.result()).isEqualTo(result);
            assertThat(event.committedAt()).isNotNull();
        });
    }

    @Test
    void b03ADatabaseRejectionIsIsolatedByScanning() {
        String prefix = unique("B03");
        StreamChunkResult result = run(poll(prefix, 500, Map.of(36, "poison"), nextOffsets()));

        assertThat(result.writeMode()).isEqualTo(WriteMode.SCAN);
        assertThat(facts(prefix)).isEqualTo(499);
        assertThat(deadLetters(prefix))
                .singleElement()
                .satisfies(d -> assertThat(d)
                        .containsEntry("stage", "LOAD")
                        .containsEntry("error_class", "DataIntegrityViolation"));
        assertThat(batchRow(result.batchId()))
                .containsEntry("write_mode", "SCAN")
                .containsEntry("records_written", 499)
                .containsEntry("records_skipped", 1);
        assertThat(db.jdbc.queryForObject(
                        "SELECT count(*) FROM ops.etl_stream_batch WHERE batch_id = ?", Long.class, result.batchId()))
                .isEqualTo(1);
    }

    @Test
    void aRolledBackChunkLeavesNoDataNoDeadLetterAndAFailedRow() {
        String prefix = unique("RB");
        List<InboundMessage> messages = poll(prefix, 10, Map.of(3, "bad"), nextOffsets());
        FAULTS.arm(FaultPoint.AFTER_WRITE_BEFORE_COMMIT, FaultAction.THROW_TRANSIENT, 0, 1);
        StreamChunkRequest failed = request(messages, false);

        assertThatThrownBy(() -> template(false).execute(failed, new TestProcessor(), db.writer(true)))
                .isInstanceOf(TransientInfraException.class);
        assertThat(facts(prefix)).isZero();
        assertThat(deadLetters(prefix)).isEmpty();
        assertThat(batchRow(failed.batchId()))
                .containsEntry("status", "FAILED")
                .containsEntry("error_class", "TransientInfraException");
        assertThat(EVENTS).isEmpty();

        StreamChunkResult retried = run(messages);
        assertThat(retried.written()).isEqualTo(9);
        assertThat(deadLetters(prefix)).hasSize(1);
    }

    @Test
    void r04ARedeliveredPollKeepsOneDeadLetter() {
        String prefix = unique("R04");
        List<InboundMessage> messages = poll(prefix, 20, Map.of(5, "bad"), nextOffsets());
        run(messages);
        StreamChunkResult again = run(messages);

        assertThat(again.written()).isZero();
        assertThat(again.duplicate()).isEqualTo(19);
        assertThat(deadLetters(prefix)).hasSize(1);
        assertThat(facts(prefix)).isEqualTo(19);
    }

    @Test
    void r02AReplayThatFailsAgainRefreshesTheDeadLetter() {
        String prefix = unique("R02");
        List<InboundMessage> messages = poll(prefix, 3, Map.of(1, "bad"), nextOffsets());
        run(messages);
        template(false).execute(request(messages, true), new TestProcessor(), db.writer(true));

        assertThat(deadLetters(prefix))
                .singleElement()
                .satisfies(d -> assertThat(d).containsEntry("replay_count", 1).containsEntry("status", "NEW"));
        assertThat(db.jdbc.queryForObject("""
                        SELECT count(*) FROM ops.dlq_action_log l JOIN ops.dead_letter d ON d.id = l.dead_letter_id
                        WHERE d.business_key = ? AND l.action = 'REPLAY_FAILED' AND l.actor = 'system:etl-batch'
                        """, Long.class, prefix + "-1")).isEqualTo(1);
    }

    @Test
    void b14BaselineFailBatchFailsTheWholePoll() {
        String prefix = unique("B14");
        List<InboundMessage> messages = poll(prefix, 10, Map.of(2, "bad"), nextOffsets());

        assertThatThrownBy(() -> template(true).execute(request(messages, false), new TestProcessor(), db.writer(true)))
                .isInstanceOf(DataBatchFailedException.class);
        assertThat(facts(prefix)).isZero();
        assertThat(deadLetters(prefix)).isEmpty();
    }

    @Test
    void aTransientErrorBeforeWritingCommitsNothing() {
        String prefix = unique("B04");
        List<InboundMessage> messages = poll(prefix, 10, Map.of(), nextOffsets());
        FAULTS.arm(FaultPoint.BEFORE_WRITE, FaultAction.THROW_TRANSIENT, 0, 2);
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> run(messages)).isInstanceOf(TransientInfraException.class);
        }
        assertThat(facts(prefix)).isZero();
        assertThat(run(messages).written()).isEqualTo(10);
        assertThat(deadLetters(prefix)).isEmpty();
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> faults() {
        List<org.junit.jupiter.params.provider.Arguments> cases = new ArrayList<>();
        for (FaultPoint point : List.of(
                FaultPoint.BEFORE_PROCESS,
                FaultPoint.AFTER_PROCESS,
                FaultPoint.BEFORE_WRITE,
                FaultPoint.AFTER_WRITE_BEFORE_COMMIT,
                FaultPoint.AFTER_COMMIT_BEFORE_ACK)) {
            for (FaultAction action : List.of(FaultAction.THROW_TRANSIENT, FaultAction.HALT)) {
                cases.add(org.junit.jupiter.params.provider.Arguments.of(point, action));
            }
        }
        return cases.stream();
    }

    /**
     * B-06, streaming half: a failure at any point of the second poll makes the container deliver that poll again,
     * as it does when the offsets were not committed. The result has every message exactly once; a failure after the
     * commit shows up as duplicates of the redelivered poll, not as extra rows. HALT is a thrown error here, which
     * ends the transaction the way a dead process does.
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "{0} {1}")
    @org.junit.jupiter.params.provider.MethodSource("faults")
    void b06EveryFaultPointEndsWithEveryMessageExactlyOnce(FaultPoint point, FaultAction action) {
        String prefix = unique("B06");
        List<InboundMessage> all = poll(prefix, 2000, Map.of(), nextOffsets());
        FAULTS.arm(point, action, 1, 1);
        int failures = 0;
        long duplicates = 0;
        for (int from = 0; from < all.size(); from += 500) {
            List<InboundMessage> poll = all.subList(from, from + 500);
            while (true) {
                try {
                    duplicates += run(poll).duplicate();
                    break;
                } catch (RuntimeException e) {
                    failures++;
                    assertThat(failures).as("the fault fires once").isEqualTo(1);
                }
            }
        }

        assertThat(failures).isEqualTo(1);
        assertThat(facts(prefix)).isEqualTo(2000);
        assertThat(deadLetters(prefix)).isEmpty();
        if (point == FaultPoint.AFTER_COMMIT_BEFORE_ACK) {
            assertThat(duplicates).as("the redelivered poll is recognised").isEqualTo(500);
        } else {
            assertThat(duplicates).isZero();
        }
    }
}
