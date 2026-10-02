package dev.pti.etl.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.pti.common.error.FatalException;
import dev.pti.common.time.BusinessClock;
import dev.pti.etl.batch.BatchSchedules;
import dev.pti.etl.batch.PtiJob;
import dev.pti.etl.batch.PtiJobLauncher;
import dev.pti.etl.batch.StepValues;
import dev.pti.etl.batch.UnreadableRecordException;
import dev.pti.etl.core.EtlSource;
import dev.pti.etl.core.InboundMessage;
import dev.pti.etl.core.WriteSet;
import dev.pti.etl.raw.RawZone;
import dev.pti.etl.replay.ReplayRequests.Kind;
import dev.pti.etl.replay.ReplayRequests.ReplayRequest;
import dev.pti.etl.write.ChunkWriter;
import dev.pti.etl.write.WriteOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.test.MetaDataInstanceFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;

class ReplayUnitTest {

    private static final String TOPIC = "ticketing.sales.cdc";
    private static final Instant HOUR = Instant.parse("2026-09-27T10:00:00Z");

    @Test
    void objectKeysAndRanges() {
        assertThat(RawObjectKey.parse(
                        TOPIC + "/dt=2026-09-27/hh=10/" + TOPIC + "-3-00000000000000000150.json.gz", TOPIC))
                .hasValueSatisfying(k -> {
                    assertThat(k.partition()).isEqualTo(3);
                    assertThat(k.startOffset()).isEqualTo(150);
                });
        assertThat(RawObjectKey.parse(TOPIC + "/x/other.json", TOPIC)).isEmpty();

        ReplayRange range =
                new ReplayRange(Instant.parse("2026-09-27T10:30:00Z"), Instant.parse("2026-09-27T12:00:00Z"));
        assertThat(range.hourPrefixes(TOPIC))
                .containsExactly(TOPIC + "/dt=2026-09-27/hh=10/", TOPIC + "/dt=2026-09-27/hh=11/");
        assertThat(range.contains(Instant.parse("2026-09-27T12:00:00Z"))).isFalse();
        assertThat(range.contains(Instant.parse("2026-09-27T10:30:00Z"))).isTrue();
        assertThatThrownBy(() -> new ReplayRange(HOUR, HOUR)).isInstanceOf(IllegalArgumentException.class);
    }

    private static String line(long offset, Instant ts, String value) {
        return "{\"key\":\"k\",\"value\":\""
                + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))
                + "\",\"offset\":" + offset + ",\"timestamp\":\"" + ts
                + "\",\"headers\":[{\"key\":\"h\",\"value\":\"v\"},"
                + "{\"key\":\"n\",\"value\":null}]}";
    }

    @Test
    void rawLinesBecomeTheSameInboundMessage() {
        InboundMessage m = RawLines.parse(EtlSource.TICKETING_SALES, TOPIC, 4, line(9, HOUR, "{}"));
        assertThat(m.partition()).isEqualTo(4);
        assertThat(m.offset()).isEqualTo(9);
        assertThat(m.recordTimestamp()).isEqualTo(HOUR);
        assertThat(m.headers()).containsExactly(Map.entry("h", "v"));
        assertThat(new String(m.value(), StandardCharsets.UTF_8)).isEqualTo("{}");
        assertThat(RawLines.parse(
                                EtlSource.TICKETING_SALES,
                                TOPIC,
                                4,
                                "{\"key\":null,\"value\":null,\"offset\":1,\"timestamp\":\"" + HOUR + "\"}")
                        .value())
                .isNull();

        assertThatThrownBy(() -> RawLines.parse(EtlSource.TICKETING_SALES, TOPIC, 4, "garbage"))
                .isInstanceOfSatisfying(
                        UnreadableRecordException.class,
                        e -> assertThat(e.message().offset()).isNull());
        assertThatThrownBy(() -> RawLines.parse(
                        EtlSource.TICKETING_SALES,
                        TOPIC,
                        4,
                        "{\"value\":\"%%%\",\"offset\":3,\"timestamp\":\"" + HOUR + "\"}"))
                .isInstanceOfSatisfying(
                        UnreadableRecordException.class,
                        e -> assertThat(e.message().offset()).isEqualTo(3));
        assertThatThrownBy(
                        () -> RawLines.parse(EtlSource.TICKETING_SALES, TOPIC, 4, "{\"offset\":3,\"timestamp\":\"x\"}"))
                .hasMessageContaining("timestamp");
        assertThatThrownBy(
                        () -> RawLines.parse(EtlSource.TICKETING_SALES, TOPIC, 4, "{\"timestamp\":\"" + HOUR + "\"}"))
                .hasMessageContaining("offset");
    }

    /** A raw zone in memory. */
    private static final class MemoryRawZone implements RawZone {
        final Map<String, byte[]> objects = new HashMap<>();

        @Override
        public String bucket() {
            return "raw";
        }

        @Override
        public String key(String relative) {
            return relative;
        }

        @Override
        public boolean exists(String key) {
            return objects.containsKey(key);
        }

        @Override
        public void put(String key, java.nio.file.Path file) {}

        @Override
        public void download(String key, java.nio.file.Path target) {}

        @Override
        public List<String> list(String prefix) {
            return objects.keySet().stream().filter(k -> k.startsWith(prefix)).toList();
        }

        @Override
        public java.io.InputStream open(String key) {
            byte[] bytes = objects.get(key);
            if (bytes == null) {
                throw new RawObjectMissingException(key);
            }
            return new ByteArrayInputStream(bytes);
        }

        void gz(int partition, long start, String... lines) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
                gz.write((String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
            }
            objects.put(name(partition, start), out.toByteArray());
        }

        static String name(int partition, long start) {
            return TOPIC + "/dt=2026-09-27/hh=10/" + TOPIC + "-" + partition + "-" + String.format("%020d", start)
                    + ".json.gz";
        }
    }

    private static StepExecution replayStep() {
        return MetaDataInstanceFactory.createStepExecution(new JobParametersBuilder()
                .addString("source", "TICKETING_SALES")
                .addString("fromTs", HOUR.plusSeconds(10).toString())
                .addString("toTs", HOUR.plusSeconds(3600).toString())
                .toJobParameters());
    }

    private static List<InboundMessage> readAll(RawZoneReader reader) {
        List<InboundMessage> read = new ArrayList<>();
        while (true) {
            try {
                InboundMessage m = reader.read();
                if (m == null) {
                    return read;
                }
                read.add(m);
            } catch (UnreadableRecordException e) {
                read.add(null);
            }
        }
    }

    @Test
    void theReaderDeduplicatesFiltersAndRestartsWhereItStopped() throws IOException {
        MemoryRawZone raw = new MemoryRawZone();
        raw.gz(
                3,
                100,
                line(100, HOUR.plusSeconds(20), "a"),
                line(101, HOUR.plusSeconds(5), "early"),
                line(102, HOUR.plusSeconds(20), "b"));
        raw.gz(
                3,
                101,
                line(101, HOUR.plusSeconds(20), "dup"),
                line(102, HOUR.plusSeconds(20), "dup"),
                "",
                line(103, HOUR.plusSeconds(20), "c"));
        raw.gz(5, 0, "not json", line(1, HOUR.plusSeconds(20), "d"));
        raw.objects.put(MemoryRawZone.name(6, 0), "not gzip".getBytes(StandardCharsets.UTF_8));
        StepExecution step = replayStep();
        StepSynchronizationManager.register(step);
        try {
            RawZoneReader reader = new RawZoneReader(raw);
            ExecutionContext context = new ExecutionContext();
            reader.open(context);
            InboundMessage first = reader.read();
            reader.update(context);
            reader.close();
            assertThat(new String(first.value(), StandardCharsets.UTF_8)).isEqualTo("a");

            RawZoneReader restarted = new RawZoneReader(raw);
            restarted.open(context);
            List<InboundMessage> rest = readAll(restarted);
            restarted.update(context);
            restarted.close();

            assertThat(rest.stream().map(m -> m == null ? "!" : new String(m.value(), StandardCharsets.UTF_8)))
                    .containsExactly("b", "c", "!", "d", "!");
            assertThat(context.getLong(RawZoneReader.DUPLICATE)).isEqualTo(2);
            assertThat(context.getLong(RawZoneReader.FILTERED)).isEqualTo(1);
            assertThat(context.getLong(RawZoneReader.OBJECTS)).isEqualTo(4);
        } finally {
            StepSynchronizationManager.close();
        }
    }

    @Test
    void aVanishedObjectIsFatal() {
        RawZone raw = mock(RawZone.class);
        when(raw.key(anyString())).thenAnswer(i -> i.getArgument(0));
        when(raw.list(anyString())).thenReturn(List.of(MemoryRawZone.name(0, 0)));
        when(raw.open(anyString())).thenThrow(new RawZone.RawObjectMissingException("k"));
        StepSynchronizationManager.register(replayStep());
        try {
            RawZoneReader reader = new RawZoneReader(raw);
            reader.open(new ExecutionContext());
            assertThatThrownBy(reader::read).isInstanceOf(FatalException.class);
        } finally {
            StepSynchronizationManager.close();
        }
    }

    @Test
    void listObjectsCountsAndRefusesHugeWindows() throws IOException {
        MemoryRawZone raw = new MemoryRawZone();
        raw.gz(0, 0, "x");
        raw.gz(1, 0, "x");
        raw.objects.put(TOPIC + "/dt=2026-09-27/hh=10/unexpected.txt", new byte[0]);
        StepExecution step = replayStep();
        StepContribution contribution = new StepContribution(step);

        new ListObjectsTasklet(raw, 10).execute(contribution, null);
        assertThat(step.getJobExecution().getExecutionContext().getLong(ListObjectsTasklet.OBJECT_COUNT))
                .isEqualTo(2);
        assertThat(contribution.getExitStatus().getExitDescription()).isEqualTo("2 raw objects in range");
        assertThatThrownBy(() -> new ListObjectsTasklet(raw, 1).execute(new StepContribution(replayStep()), null))
                .hasMessageContaining("split it");
    }

    private static StepExecution writerStep() {
        StepExecution step = MetaDataInstanceFactory.createStepExecution(new JobParametersBuilder()
                .addString("replayRequestId", UUID.randomUUID().toString())
                .toJobParameters());
        step.getExecutionContext()
                .putString(StepValues.BATCH_ID, UUID.randomUUID().toString());
        step.getExecutionContext()
                .putString(DeadLetterReader.DEAD_LETTER_ID, UUID.randomUUID().toString());
        return step;
    }

    private static WriteSet set(Instant event) {
        WriteSet set = mock(WriteSet.class);
        when(set.eventTimestamp()).thenReturn(event);
        when(set.origin())
                .thenReturn(new InboundMessage(EtlSource.TICKETING_SALES, null, null, TOPIC, 0, 1L, null, Map.of()));
        return set;
    }

    private static final BusinessClock CLOCK = new BusinessClock(Clock.fixed(HOUR, ZoneOffset.UTC), Duration.ZERO);

    @Test
    void theReplayWriterResolvesDeadLettersAndKeepsTheEventRange() {
        DeadLetterReplays deadLetters = mock(DeadLetterReplays.class);
        when(deadLetters.resolve(any(), any())).thenReturn(1);
        List<Boolean> replayFlags = new ArrayList<>();
        ChunkWriter writer = (items, context) -> {
            replayFlags.add(context.replay());
            return new WriteOutcome(List.copyOf(items), 0, 0, 0, 0);
        };
        StepExecution step = writerStep();
        ReplayChunkWriter replay = new ReplayChunkWriter(writer, deadLetters, CLOCK, () -> step);

        replay.write(new Chunk<>(List.of(set(HOUR.plusSeconds(50)), set(HOUR.plusSeconds(10)))));
        replay.write(new Chunk<>(List.of(set(HOUR.plusSeconds(90)))));

        assertThat(replayFlags).containsOnly(true);
        assertThat(step.getExecutionContext().getLong(ReplayChunkWriter.RESOLVED))
                .isEqualTo(2);
        assertThat(step.getExecutionContext().getString(ReplayChunkWriter.MIN_EVENT_TS))
                .isEqualTo(HOUR.plusSeconds(10).toString());
        assertThat(step.getExecutionContext().getString(ReplayChunkWriter.MAX_EVENT_TS))
                .isEqualTo(HOUR.plusSeconds(90).toString());
    }

    @Test
    void theDlqWriterMarksTheRowReplayedUnlessARuleRejectedIt() {
        DeadLetterReplays deadLetters = mock(DeadLetterReplays.class);
        StepExecution step = writerStep();
        int[] rejected = {0};
        ChunkWriter writer = (items, context) -> new WriteOutcome(List.copyOf(items), 0, 0, 0, rejected[0]);
        DlqReplayWriter dlq = new DlqReplayWriter(writer, deadLetters, CLOCK, () -> step);

        dlq.write(new Chunk<>());
        dlq.write(new Chunk<>(List.of(set(HOUR))));
        rejected[0] = 1;
        dlq.write(new Chunk<>(List.of(set(HOUR))));

        verify(deadLetters, org.mockito.Mockito.times(1)).markReplayed(any(), any(), any(), eq(1));
    }

    @Test
    void thePollerStartsTheRightJobOrFailsTheRequest() throws Exception {
        ReplayRequests requests = mock(ReplayRequests.class);
        PtiJobLauncher launcher = mock(PtiJobLauncher.class);
        BatchSchedules schedules = mock(BatchSchedules.class);
        ReplayRequestPoller poller = new ReplayRequestPoller(requests, launcher, schedules, () -> true, 3);
        ReplayRequest raw = new ReplayRequest(
                UUID.randomUUID(), Kind.RAW_RANGE, EtlSource.TICKETING_SALES, HOUR, HOUR.plusSeconds(60), false);
        ReplayRequest dlq =
                new ReplayRequest(UUID.randomUUID(), Kind.DLQ_RECORD, EtlSource.TICKETING_SALES, null, null, false);
        ReplayRequest analytics = new ReplayRequest(
                UUID.randomUUID(), Kind.RAW_RANGE, EtlSource.TICKETING_SALES, HOUR, HOUR.plusSeconds(60), true);
        when(requests.claim())
                .thenReturn(Optional.of(raw))
                .thenReturn(Optional.of(dlq))
                .thenReturn(Optional.of(analytics));

        poller.scheduled();
        verify(requests, never()).claim();
        when(schedules.isReady()).thenReturn(true);
        when(launcher.start(eq(PtiJob.DLQ_REPLAY), any())).thenThrow(new TaskRejectedException("full"));
        poller.scheduled();

        verify(launcher, org.mockito.Mockito.times(2)).start(eq(PtiJob.RAW_ZONE_REPLAY), any());
        verify(requests).fail(eq(dlq.id()), org.mockito.ArgumentMatchers.contains("executor is full"));
        verify(requests, never()).fail(eq(analytics.id()), anyString());
        JobParameters parameters = ReplayRequestPoller.parameters(PtiJob.RAW_ZONE_REPLAY, raw);
        assertThat(parameters.getString("source")).isEqualTo("TICKETING_SALES");
        assertThat(parameters.getString("replay")).isEqualTo("true");
        assertThat(parameters.getString("recomputeAnalytics")).isEqualTo("false");
        assertThat(parameters.getIdentifyingParameters()).hasSize(1);
        assertThat(ReplayRequestPoller.parameters(PtiJob.RAW_ZONE_REPLAY, analytics)
                        .getString("recomputeAnalytics"))
                .isEqualTo("true");
    }

    @Test
    void theListenerFinishesTheRequestWithStats() {
        ReplayRequests requests = mock(ReplayRequests.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString(), any(Object[].class)))
                .thenReturn(Map.of("status", "NEW", "stage", "QUALITY", "rule_id", "DQ-10"));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ReplayRequestListener listener = new ReplayRequestListener(requests, jdbc, meters, mock(JobRepository.class));
        UUID id = UUID.randomUUID();
        JobParameters parameters = new JobParametersBuilder()
                .addString("replayRequestId", id.toString())
                .toJobParameters();

        JobExecution rawRun = MetaDataInstanceFactory.createJobExecution("RawZoneReplayJob", 1L, 2L, parameters);
        StepExecution records = MetaDataInstanceFactory.createStepExecution(rawRun, "replayRecords", 3L);
        rawRun.addStepExecution(records);
        records.getExecutionContext().putLong(RawZoneReader.LINES_READ, 10);
        records.getExecutionContext().putString(ReplayChunkWriter.MIN_EVENT_TS, HOUR.toString());
        records.getExecutionContext().putString(ReplayChunkWriter.MAX_EVENT_TS, HOUR.toString());
        rawRun.setStatus(BatchStatus.COMPLETED);
        listener.beforeJob(rawRun);
        listener.afterJob(rawRun);
        verify(requests).running(id, 2L);
        verify(requests)
                .finish(eq(id), eq(2L), eq(true), org.mockito.ArgumentMatchers.contains("\"lines_read\":10"), eq(null));

        JobExecution dlqRun = MetaDataInstanceFactory.createJobExecution("DlqReplayJob", 4L, 5L, parameters);
        dlqRun.setStatus(BatchStatus.FAILED);
        dlqRun.setExitStatus(ExitStatus.FAILED.addExitDescription("Dead letter x is NEW\n at stack"));
        listener.afterJob(dlqRun);
        verify(requests)
                .finish(
                        eq(id),
                        eq(5L),
                        eq(false),
                        org.mockito.ArgumentMatchers.contains("FAILED_AGAIN"),
                        eq("Dead letter x is NEW"));
        assertThat(meters.get("pti.replay.requests")
                        .tag("outcome", "failed")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }
}
