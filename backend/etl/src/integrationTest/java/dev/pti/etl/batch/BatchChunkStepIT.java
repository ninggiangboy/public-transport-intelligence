package dev.pti.etl.batch;

import static dev.pti.etl.WarehouseSupport.NOW;
import static dev.pti.etl.WarehouseSupport.hash;
import static dev.pti.etl.WarehouseSupport.vp;
import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.common.error.DeserializationException;
import dev.pti.etl.core.EtlSource;
import dev.pti.etl.core.InboundMessage;
import dev.pti.etl.core.VehiclePositionRow;
import dev.pti.etl.core.WriteSet;
import dev.pti.etl.write.FactChunkWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.support.AbstractItemCountingItemStreamItemReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.TransientDataAccessResourceException;

/** The fault-tolerant chunk step of DOC-19 §5 on Spring Batch 6 with PostgreSQL (DOC-19 §12, batch half). */
class BatchChunkStepIT extends BatchContextSupport {

    @Autowired
    BatchSteps steps;

    @Autowired
    BatchChunkWriter writer;

    @Autowired
    FactChunkWriter factWriter;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    RatioSkipPolicy skipPolicy;

    @Autowired
    TransientRetryPolicy retryPolicy;

    @Autowired
    DeadLetterSkipListener deadLetterListener;

    @Autowired
    BatchIdStepListener batchIdListener;

    @Autowired
    FaultStepListener faultListener;

    @Autowired
    JobRequestListener jobRequestListener;

    /** Items read from a list; restartable, because it keeps {@code read.count} in the step context. */
    static final class ListReader extends AbstractItemCountingItemStreamItemReader<InboundMessage> {

        private final List<InboundMessage> items;
        private int next;

        ListReader(List<InboundMessage> items) {
            this.items = items;
            setName("listReader");
        }

        @Override
        protected InboundMessage doRead() {
            return next < items.size() ? items.get(next++) : null;
        }

        @Override
        protected void doOpen() {
            next = 0;
        }

        @Override
        protected void doClose() {}
    }

    /** "bad" does not parse, "poison" breaks a CHECK constraint (latitude 95), anything else is valid. */
    static final ItemProcessor<InboundMessage, WriteSet> PROCESSOR = message -> {
        String value = new String(message.value(), StandardCharsets.UTF_8);
        if (value.equals("bad")) {
            throw new DeserializationException("Unexpected character", null);
        }
        double lat = value.equals("poison") ? 95.0 : 44.97;
        VehiclePositionRow row = vp(message.key(), NOW, lat, hash(message.key() + value));
        return WriteSet.vehiclePosition(message, row.payloadHash(), row.vehicleId() + "|" + NOW, row);
    };

    private static List<InboundMessage> items(String prefix, int size, Map<Integer, String> special) {
        long firstOffset = ThreadLocalRandom.current().nextLong(1_000_000_000L, 1_000_000_000_000L);
        FIRST_OFFSETS.put(prefix, firstOffset);
        List<InboundMessage> messages = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            messages.add(new InboundMessage(
                    EtlSource.GTFS_RT_VEHICLE_POSITION,
                    prefix + "-" + i,
                    special.getOrDefault(i, "ok").getBytes(StandardCharsets.UTF_8),
                    "gtfs.vehicle_positions",
                    5,
                    firstOffset + i,
                    NOW.minusSeconds(1),
                    Map.of()));
        }
        return messages;
    }

    private static final Map<String, Long> FIRST_OFFSETS = new java.util.concurrent.ConcurrentHashMap<>();

    private static long firstOffset(String prefix) {
        return FIRST_OFFSETS.get(prefix);
    }

    private static String prefix() {
        return "B" + UUID.randomUUID().toString().substring(0, 8);
    }

    private Job job(String prefix, List<InboundMessage> items, int chunkSize, ItemWriter<WriteSet> itemWriter) {
        return job(steps, prefix, items, chunkSize, itemWriter);
    }

    private static Job job(
            BatchSteps steps, String prefix, List<InboundMessage> items, int chunkSize, ItemWriter<WriteSet> w) {
        return steps.job("Test" + prefix + "Job")
                .start(steps.chunk("load", chunkSize, new ListReader(items), PROCESSOR, w))
                .build();
    }

    private JobExecution run(Job job, JobParameters parameters) throws Exception {
        return awaitEnd(jobOperator.start(job, parameters));
    }

    private static JobParameters parameters() {
        return new JobParametersBuilder()
                .addString("run", UUID.randomUUID().toString())
                .toJobParameters();
    }

    /** Wraps the real writer: {@code before} sees every chunk before it is written. */
    private ItemWriter<WriteSet> writer(Consumer<Chunk<? extends WriteSet>> before) {
        return chunk -> {
            before.accept(chunk);
            writer.write(chunk);
        };
    }

    private long facts(String prefix) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM dw.fact_vehicle_position WHERE vehicle_id LIKE ?", Long.class, prefix + "-%");
    }

    /** Dead letters of the job's steps; a message that does not parse has no business key to search by. */
    private List<Map<String, Object>> deadLetters(String prefix) {
        return jdbc.queryForList("""
                SELECT stage, error_class, business_key, batch_id, kafka_offset FROM ops.dead_letter
                WHERE batch_id IN (SELECT batch_id FROM ops.etl_batch_step WHERE job_name = ?)
                ORDER BY kafka_offset
                """, "Test" + prefix + "Job");
    }

    private static StepExecution step(JobExecution execution) {
        return execution.getStepExecutions().iterator().next();
    }

    @Test
    void p201AJobRunsOnTheAsyncExecutorAndItsMetadataIsInTheBatchSchema() throws Exception {
        String prefix = prefix();
        JobExecution execution = run(job(prefix, items(prefix, 3, Map.of()), 500, writer), parameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(jdbc.queryForObject("""
                        SELECT ji.job_name FROM batch.batch_job_execution je
                        JOIN batch.batch_job_instance ji USING (job_instance_id) WHERE je.job_execution_id = ?
                        """, String.class, execution.getId())).isEqualTo("Test" + prefix + "Job");
        assertThat(jdbc.queryForObject(
                        "SELECT short_context FROM batch.batch_step_execution_context WHERE step_execution_id = ?",
                        String.class,
                        step(execution).getId()))
                .as("DR-62: the execution context is JSON")
                .contains("\"pti.batchId\"");
    }

    @Test
    void b01AnUnparsableItemIsSkippedToTheDeadLetterQueue() throws Exception {
        String prefix = prefix();
        JobExecution execution = run(job(prefix, items(prefix, 500, Map.of(17, "bad")), 500, writer), parameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(step(execution).getExitStatus().getExitCode()).isEqualTo("COMPLETED_WITH_SKIPS");
        assertThat(facts(prefix)).isEqualTo(499);
        List<Map<String, Object>> dlq = deadLetters(prefix);
        assertThat(dlq).hasSize(1);
        assertThat(dlq.getFirst())
                .containsEntry("stage", "DESERIALIZE")
                .containsEntry("kafka_offset", firstOffset(prefix) + 17)
                .containsEntry(
                        "batch_id",
                        UUID.fromString(step(execution).getExecutionContext().getString("pti.batchId")));
    }

    @Test
    void b03ADatabaseRejectionIsIsolatedByScanning() throws Exception {
        String prefix = prefix();
        JobExecution execution = run(job(prefix, items(prefix, 500, Map.of(37, "poison")), 500, writer), parameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(step(execution).getWriteSkipCount()).isEqualTo(1);
        assertThat(facts(prefix)).isEqualTo(499);
        assertThat(deadLetters(prefix))
                .singleElement()
                .satisfies(row -> assertThat(row)
                        .containsEntry("stage", "LOAD")
                        .containsEntry("error_class", "DataIntegrityViolation")
                        .containsEntry("business_key", prefix + "-37|" + NOW));
    }

    @Test
    void b05DeadLettersOfAChunkThatRollsBackAreRolledBackToo() throws Exception {
        String prefix = prefix();
        ItemWriter<WriteSet> failing = writer(chunk -> {
            throw new TransientDataAccessResourceException("connection reset");
        });
        JobExecution execution = run(job(prefix, items(prefix, 20, Map.of(3, "bad")), 500, failing), parameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(facts(prefix)).isZero();
        assertThat(deadLetters(prefix))
                .as("the skip listener wrote inside the rolled-back chunk")
                .isEmpty();
    }

    @Test
    void b11ASkipRatioAboveTheLimitFailsTheStep() throws Exception {
        String prefix = prefix();
        Map<Integer, String> bad = new java.util.HashMap<>();
        for (int i = 0; i < 1000; i += 4) {
            bad.put(i, "bad");
        }
        JobExecution execution = run(job(prefix, items(prefix, 1000, bad), 100, writer), parameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(step(execution).getExitStatus().getExitDescription()).contains("is above the limit 0.200");
        assertThat(facts(prefix)).isZero();
    }

    @Test
    void b14BaselineFailBatchFailsTheStepOnTheFirstBadItem() throws Exception {
        String prefix = prefix();
        BatchSteps failBatch = new BatchSteps(
                steps.jobRepository(),
                steps.transactionManager(),
                skipPolicy,
                retryPolicy,
                TransientRetryPolicy.backOff(java.time.Duration.ofMillis(10), 2.0, java.time.Duration.ofMillis(40)),
                deadLetterListener,
                batchIdListener,
                faultListener,
                List.of(jobRequestListener),
                true);
        JobExecution execution =
                run(job(failBatch, prefix, items(prefix, 50, Map.of(7, "bad")), 500, writer), parameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(facts(prefix)).isZero();
        assertThat(deadLetters(prefix)).isEmpty();
    }

    @Test
    void b15ARestartGetsANewBatchIdWhileCommittedRowsKeepTheirs() throws Exception {
        String prefix = prefix();
        AtomicBoolean failOnce = new AtomicBoolean(true);
        ItemWriter<WriteSet> failSecondChunk = writer(chunk -> {
            if (chunk.getItems().getFirst().businessKey().startsWith(prefix + "-100|") && failOnce.getAndSet(false)) {
                throw new IllegalStateException("Injected bug in chunk 2");
            }
        });
        Job job = job(prefix, items(prefix, 200, Map.of()), 100, failSecondChunk);
        JobParameters parameters = parameters();
        JobExecution first = run(job, parameters);
        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(facts(prefix)).isEqualTo(100);

        JobExecution second = run(job, parameters);
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(step(second).getReadCount())
                .as("continues after the committed chunk")
                .isEqualTo(100);
        assertThat(facts(prefix)).isEqualTo(200);

        List<UUID> stepBatchIds = jdbc.queryForList(
                "SELECT batch_id FROM ops.etl_batch_step WHERE job_execution_id IN (?, ?) ORDER BY created_at",
                UUID.class,
                first.getId(),
                second.getId());
        assertThat(stepBatchIds).hasSize(2).doesNotHaveDuplicates();
        assertThat(jdbc.queryForList(
                        "SELECT DISTINCT batch_id FROM dw.fact_vehicle_position WHERE vehicle_id LIKE ?",
                        UUID.class,
                        prefix + "-%"))
                .containsExactlyInAnyOrderElementsOf(stepBatchIds);
    }

    @Test
    void b18ACrashInTheMiddleOfAScanLosesNoItemAfterRestart() throws Exception {
        String prefix = prefix();
        AtomicBoolean crashOnce = new AtomicBoolean(true);
        AtomicInteger scanned = new AtomicInteger();
        ItemWriter<WriteSet> crashing = writer(chunk -> {
            if (chunk.size() == 1 && scanned.incrementAndGet() == 300 && crashOnce.getAndSet(false)) {
                throw new SimulatedCrash();
            }
        });
        Job job = job(prefix, items(prefix, 500, Map.of(37, "poison")), 500, crashing);
        JobParameters parameters = parameters();
        JobExecution first = run(job, parameters);
        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);

        JobExecution second = run(job, parameters);
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(facts(prefix)).as("DR-80: no item of the chunk is lost").isEqualTo(499);
        assertThat(deadLetters(prefix)).extracting(row -> row.get("stage")).containsExactly("LOAD");
    }

    @Test
    void b17AReplayRewritesFactsAndLeavesTheRegistryAlone() throws Exception {
        String prefix = prefix();
        List<InboundMessage> messages = items(prefix, 5, Map.of());
        assertThat(run(job(prefix, messages, 500, writer), parameters()).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);

        JobParameters replay = new JobParametersBuilder(parameters())
                .addString(StepValues.REPLAY, "true", false)
                .toJobParameters();
        JobExecution replayed = run(job(prefix + "r", messages, 500, writer), replay);

        assertThat(replayed.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(step(replayed).getWriteCount()).isEqualTo(5);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM ops.dedup_registry WHERE batch_id = ?::uuid",
                        Long.class,
                        step(replayed).getExecutionContext().getString("pti.batchId")))
                .isZero();
        assertThat(Set.copyOf(jdbc.queryForList(
                        "SELECT batch_id::text FROM dw.fact_vehicle_position WHERE vehicle_id LIKE ?",
                        String.class,
                        prefix + "-%")))
                .as("the replay rewrote the facts under its own batch_id")
                .containsExactly(step(replayed).getExecutionContext().getString("pti.batchId"));
    }

    /** Stands in for {@code kill -9} during a scan: an Error that the chunk loop does not handle. */
    static final class SimulatedCrash extends Error {
        private static final long serialVersionUID = 1L;

        SimulatedCrash() {
            super("Simulated crash during scan");
        }
    }
}
