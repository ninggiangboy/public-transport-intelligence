package dev.pti.etl.stream.application;

import static dev.pti.etl.testing.EtlFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.common.classifier.PlatformErrorClassifier;
import dev.pti.common.dq.DlqStage;
import dev.pti.common.error.DeserializationException;
import dev.pti.common.error.FatalException;
import dev.pti.common.error.TransientInfraException;
import dev.pti.common.time.BusinessClock;
import dev.pti.etl.core.application.MessageProcessor;
import dev.pti.etl.core.domain.EtlSource;
import dev.pti.etl.core.domain.InboundMessage;
import dev.pti.etl.core.domain.RuleContext;
import dev.pti.etl.core.domain.VehiclePositionRow;
import dev.pti.etl.core.domain.WriteSet;
import dev.pti.etl.fault.application.port.FaultInjector;
import dev.pti.etl.reference.application.ReferenceDataHolder;
import dev.pti.etl.stream.adapter.out.jdbc.StreamBatchLog;
import dev.pti.etl.stream.adapter.out.metrics.MicrometerStreamMetrics;
import dev.pti.etl.stream.adapter.out.tracing.NoopChunkTracing;
import dev.pti.etl.write.adapter.out.metrics.MicrometerWriteMetrics;
import dev.pti.etl.write.application.port.ChunkWriter;
import dev.pti.etl.write.application.port.DeadLetterWriter;
import dev.pti.etl.write.domain.DeadLetter;
import dev.pti.etl.write.domain.WriteMode;
import dev.pti.etl.write.domain.WriteOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.core.exc.StreamReadException;

/** DOC-19 §6.2 without a database: the transaction boundaries, the scan and what reaches the dead-letter queue. */
class StreamChunkTemplateTest {

    private final FakeChunkTransaction txManager = new FakeChunkTransaction();
    private final List<DeadLetter> live = new ArrayList<>();
    private final List<DeadLetter> replayed = new ArrayList<>();
    private final List<StreamChunkResult> logged = new ArrayList<>();
    private final List<Throwable> failed = new ArrayList<>();
    private final List<Object> events = new ArrayList<>();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private boolean deadLettersRefused;

    private final DeadLetterWriter deadLetters = new DeadLetterWriter() {
        @Override
        public DeadLetterResult write(DeadLetter letter) {
            if (deadLettersRefused) {
                throw new DataIntegrityViolationException("dead letter refused");
            }
            live.add(letter);
            return DeadLetterResult.INSERTED;
        }

        @Override
        public DeadLetterResult writeReplay(DeadLetter letter) {
            replayed.add(letter);
            return DeadLetterResult.UPDATED;
        }

        @Override
        public DeadLetter loadFailure(
                InboundMessage message, Throwable error, @Nullable String businessKey, UUID batchId) {
            return new DeadLetter(
                    message,
                    DlqStage.LOAD,
                    null,
                    DeadLetter.LOAD_ERROR_CLASS,
                    String.valueOf(error.getMessage()),
                    businessKey,
                    batchId);
        }
    };

    private final StreamBatchLog batchLog = new StreamBatchLog(new NamedParameterJdbcTemplate(new JdbcTemplate())) {
        @Override
        public void insert(
                StreamChunkRequest request, StreamChunkResult result, Instant startedAt, Instant finishedAt) {
            logged.add(result);
        }

        @Override
        public void insertFailedBestEffort(
                StreamChunkRequest request, WriteMode mode, Throwable error, Instant startedAt, Instant finishedAt) {
            failed.add(error);
        }
    };

    private StreamChunkTemplate template(boolean failBatch) {
        return new StreamChunkTemplate(
                txManager,
                deadLetters,
                batchLog,
                new PlatformErrorClassifier(),
                FaultInjector.NOOP,
                events::add,
                new BusinessClock(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ZERO),
                new ReferenceDataHolder(),
                new MicrometerWriteMetrics(meters),
                new MicrometerStreamMetrics(meters),
                NoopChunkTracing.INSTANCE,
                failBatch);
    }

    /** "bad" does not parse, "json" fails in the parser, "bug" is a programming error, anything else is valid. */
    private static final MessageProcessor PROCESSOR = new MessageProcessor() {
        @Override
        public EtlSource source() {
            return EtlSource.GTFS_RT_VEHICLE_POSITION;
        }

        @Override
        public WriteSet process(InboundMessage message, RuleContext context) {
            String value = new String(message.value(), StandardCharsets.UTF_8);
            switch (value) {
                case "bad" -> throw new DeserializationException("Unexpected character", null);
                case "json" -> throw new StreamReadException(null, "Unexpected end of input");
                case "bug" -> throw new IllegalStateException("bug");
                default -> {}
            }
            VehiclePositionRow row = new VehiclePositionRow(
                    LocalDate.parse("2026-09-29"),
                    message.key(),
                    NOW,
                    "T",
                    "18",
                    (short) 0,
                    44.9,
                    -93.2,
                    null,
                    null,
                    1,
                    "S",
                    "STOPPED_AT",
                    null,
                    (short) 2,
                    value);
            return WriteSet.vehiclePosition(message, value, message.key(), row);
        }

        @Override
        public @Nullable String businessKey(InboundMessage message) {
            if (message.key().equals("no-key")) {
                throw new IllegalStateException("cannot parse");
            }
            return message.key();
        }
    };

    /** Writes everything, except that any item with value "poison" makes the database refuse the write. */
    private final List<List<WriteSet>> writes = new ArrayList<>();

    private final ChunkWriter writer = (items, context) -> {
        writes.add(items);
        for (WriteSet item : items) {
            if (item.messageHash().equals("poison")) {
                throw new DataIntegrityViolationException("new row violates check constraint");
            }
            if (item.messageHash().equals("down")) {
                throw new TransientInfraException("connection lost", null);
            }
        }
        return new WriteOutcome(items, 0, 0, 0, 0);
    };

    private static StreamChunkRequest request(boolean replay, String... values) {
        List<InboundMessage> messages = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            String key = values[i].startsWith("key:") ? values[i].substring(4) : "v" + i;
            String value = values[i].startsWith("key:") ? "ok" : values[i];
            messages.add(new InboundMessage(
                    EtlSource.GTFS_RT_VEHICLE_POSITION,
                    key,
                    value.getBytes(StandardCharsets.UTF_8),
                    "gtfs.vehicle_positions",
                    0,
                    (long) i,
                    NOW.minusSeconds(1),
                    Map.of()));
        }
        return new StreamChunkRequest(
                UUID.randomUUID(), EtlSource.GTFS_RT_VEHICLE_POSITION, "l", "g", "i", messages, replay);
    }

    private double records(String outcome) {
        var counter = meters.find("pti.etl.records").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void aCleanPollCommitsOnce() {
        StreamChunkResult result = template(false).execute(request(false, "a", "b"), PROCESSOR, writer);

        assertThat(result.written()).isEqualTo(2);
        assertThat(result.status()).isEqualTo(StreamChunkResult.BatchStatus.COMPLETED);
        assertThat(result.writeMode()).isEqualTo(WriteMode.BATCH);
        assertThat(txManager.commits).isEqualTo(1);
        assertThat(logged).containsExactly(result);
        assertThat(events).containsExactly(new MicroBatchCommitted(result, NOW));
        assertThat(meters.find("pti.etl.kafka.to.commit").timer().count()).isEqualTo(2);
        assertThat(meters.find("pti.etl.stream.batches")
                        .tag("status", "COMPLETED")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void processingErrorsAreDeadLetteredInTheSameTransaction() {
        StreamChunkResult result =
                template(false).execute(request(false, "a", "bad", "json", "key:no-key"), PROCESSOR, writer);

        assertThat(result.written()).isEqualTo(2);
        assertThat(result.skipped()).isEqualTo(2);
        assertThat(result.status()).isEqualTo(StreamChunkResult.BatchStatus.COMPLETED_WITH_SKIPS);
        assertThat(live).extracting(DeadLetter::stage).containsOnly(DlqStage.DESERIALIZE);
        assertThat(live)
                .extracting(DeadLetter::errorClass)
                .containsExactly("DeserializationException", "StreamReadException");
        assertThat(txManager.commits).isEqualTo(1);
        assertThat(records("skipped")).isEqualTo(2);
    }

    @Test
    void aDatabaseRefusalIsIsolatedByScanningBehindSavepoints() {
        StreamChunkResult result = template(false).execute(request(false, "a", "poison", "c"), PROCESSOR, writer);

        assertThat(result.writeMode()).isEqualTo(WriteMode.SCAN);
        assertThat(result.written()).isEqualTo(2);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(txManager.rollbacks).isEqualTo(1);
        assertThat(txManager.commits).isEqualTo(1);
        assertThat(txManager.savepointRollbacks).isEqualTo(1);
        assertThat(live).singleElement().satisfies(d -> {
            assertThat(d.stage()).isEqualTo(DlqStage.LOAD);
            assertThat(d.errorClass()).isEqualTo(DeadLetter.LOAD_ERROR_CLASS);
            assertThat(d.businessKey()).isEqualTo("v1");
        });
        assertThat(writes).hasSize(4);
        assertThat(meters.find("pti.etl.chunk.scan").counter().count()).isEqualTo(1);
    }

    @Test
    void anInfrastructureErrorRollsBackAndIsRethrown() {
        assertThatThrownBy(() -> template(false).execute(request(false, "a", "down"), PROCESSOR, writer))
                .isInstanceOf(TransientInfraException.class);
        assertThat(txManager.commits).isZero();
        assertThat(failed).singleElement().isInstanceOf(TransientInfraException.class);
        assertThat(events).isEmpty();
        assertThat(records("written")).isZero();
    }

    @Test
    void anInfrastructureErrorDuringTheScanFailsThePoll() {
        assertThatThrownBy(() -> template(false).execute(request(false, "poison", "down"), PROCESSOR, writer))
                .isInstanceOf(TransientInfraException.class);
        assertThat(txManager.rollbacks).isEqualTo(2);
        assertThat(txManager.commits).isZero();
    }

    @Test
    void aProgrammingErrorInTheProcessorFailsThePoll() {
        assertThatThrownBy(() -> template(false).execute(request(false, "a", "bug"), PROCESSOR, writer))
                .isInstanceOf(IllegalStateException.class);
        assertThat(txManager.commits + txManager.rollbacks).isZero();
    }

    @Test
    void aDataErrorOutsideAnyRecordIsFatal() {
        deadLettersRefused = true;
        assertThatThrownBy(() -> template(false).execute(request(false, "a", "bad"), PROCESSOR, writer))
                .isInstanceOf(FatalException.class);
        assertThat(failed).hasSize(1);
    }

    @Test
    void replayRefreshesExistingDeadLetters() {
        template(false).execute(request(true, "a", "bad"), PROCESSOR, writer);
        assertThat(replayed).hasSize(1);
        assertThat(live).isEmpty();
    }

    @Test
    void baselineFailBatchRejectsThePollOnTheFirstBadRecord() {
        assertThatThrownBy(() -> template(true).execute(request(false, "a", "bad"), PROCESSOR, writer))
                .isInstanceOf(DataBatchFailedException.class);
        assertThat(writes).isEmpty();
    }
}
