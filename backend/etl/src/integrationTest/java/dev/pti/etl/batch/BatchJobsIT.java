package dev.pti.etl.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.pti.common.time.BusinessClock;
import dev.pti.db.MigratedDatabases;
import dev.pti.etl.batch.maintenance.PartitionMaintenanceTasklet;
import dev.pti.etl.batch.maintenance.PartitionMaintenanceTasklet.ManagedTable;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Batch jobs as operated: maintenance (DOC-18 §7), {@code job_request} (DOC-19 §7.3) and recovery (DOC-19 §7.2). */
class BatchJobsIT extends BatchContextSupport {

    @Autowired
    PtiJobLauncher launcher;

    @Autowired
    BatchSteps steps;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    JobRequestPoller poller;

    @Autowired
    StaleExecutionRecoverer recoverer;

    /** A run date nobody else uses, so that every test gets its own job instance. */
    private static String uniqueDate() {
        return LocalDate.of(2100, 1, 1)
                .plusDays(ThreadLocalRandom.current().nextInt(0, 300_000))
                .toString();
    }

    private JobExecution run(PtiJob job, String identity) {
        JobParameters parameters = JobParams.identity(job, identity).toJobParameters();
        return awaitEnd(launcher.launchIfNew(job, parameters).orElseThrow());
    }

    private boolean partitionExists(String name) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, "dw." + name));
    }

    @Test
    void l01PartitionsFollowTheBusinessDateOfTheAgency() throws Exception {
        // 12:00Z is 06:00 in Chicago on 10 March; twelve hours earlier it is still 9 March there.
        Clock real = Clock.fixed(Instant.parse("2027-03-10T12:00:00Z"), ZoneOffset.UTC);
        BusinessClock clock = new BusinessClock(real, Duration.ofHours(-12));
        PartitionMaintenanceTasklet tasklet = new PartitionMaintenanceTasklet(
                jdbc,
                clock,
                ZoneId.of("America/Chicago"),
                List.of(new ManagedTable("fact_vehicle_position", 7, Duration.ofDays(3650))),
                new SimpleMeterRegistry());
        JobExecution execution = awaitEnd(jobOperator.start(
                steps.job("TestPartitionsJob")
                        .start(steps.tasklet("maintainPartitions", tasklet))
                        .build(),
                JobParams.identity(PtiJob.PARTITION_MAINTENANCE, uniqueDate()).toJobParameters()));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(partitionExists("fact_vehicle_position_p20270308")).isTrue();
        assertThat(partitionExists("fact_vehicle_position_p20270316")).isTrue();
        assertThat(partitionExists("fact_vehicle_position_p20270317")).isFalse();
        assertThat(execution
                        .getStepExecutions()
                        .iterator()
                        .next()
                        .getExitStatus()
                        .getExitDescription())
                .contains("Business date 2027-03-09");
    }

    @Test
    void p217ThePartitionJobCreatesTheComingPartitions() {
        JobExecution execution = run(PtiJob.PARTITION_MAINTENANCE, uniqueDate());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        LocalDate today = LocalDate.now(ZoneId.of("America/Chicago"));
        assertThat(partitionExists(
                        "fact_vehicle_position_p" + today.plusDays(7).toString().replace("-", "")))
                .isTrue();
        assertThat(partitionExists("fact_ticket_sales_p"
                        + today.plusDays(40).toString().substring(0, 7).replace("-", "")))
                .isTrue();
    }

    private UUID deadLetter(String status, Duration age) {
        UUID id = UUID.randomUUID();
        boolean closed = !status.equals("NEW");
        jdbc.update(
                """
                INSERT INTO ops.dead_letter (id, source, stage, error_class, error_message, raw_payload, batch_id,
                                             status, resolved_by, resolved_at, created_at, updated_at)
                VALUES (?, 'TICKETING_SALES', 'SCHEMA', 'DQ-01', 'bad', '{}', ?, ?, ?,
                        CASE WHEN ? THEN now() - make_interval(secs => ?) END,
                        now() - make_interval(secs => ?), now() - make_interval(secs => ?))
                """,
                id,
                UUID.randomUUID(),
                status,
                closed ? "user:test" : null,
                closed,
                (double) age.toSeconds(),
                (double) age.toSeconds(),
                (double) age.toSeconds());
        jdbc.update(
                "INSERT INTO ops.dlq_action_log (dead_letter_id, action, actor) VALUES (?, 'DISCARDED', 'user:test')",
                id);
        return id;
    }

    private boolean exists(String table, UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id = ?", Long.class, id) > 0;
    }

    @Test
    void l02OpsRetentionKeepsOpenDeadLettersForever() {
        UUID oldClosed = deadLetter("DISCARDED", Duration.ofDays(40));
        UUID oldOpen = deadLetter("NEW", Duration.ofDays(40));
        UUID recentClosed = deadLetter("DISCARDED", Duration.ofDays(3));

        JobExecution execution = run(PtiJob.OPS_RETENTION, "test-" + UUID.randomUUID());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(exists("ops.dead_letter", oldClosed)).isFalse();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM ops.dlq_action_log WHERE dead_letter_id = ?", Long.class, oldClosed))
                .isZero();
        assertThat(exists("ops.dead_letter", oldOpen)).isTrue();
        assertThat(exists("ops.dead_letter", recentClosed)).isTrue();
    }

    @Test
    void l03OpsRetentionDeletesInBatchesOfFiveThousand() {
        String listener = "l03-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ops.etl_stream_batch (batch_id, source, listener_id, consumer_group, instance_id, offsets,
                    status, write_mode, records_read, records_written, records_skipped, records_duplicate,
                    started_at, finished_at)
                SELECT gen_random_uuid(), 'TICKETING_SALES', ?, 'g', 'i', '{}', 'COMPLETED', 'BATCH', 1, 1, 0, 0,
                       now() - interval '10 days', now() - interval '10 days'
                FROM generate_series(1, 120000)
                """, listener);

        JobExecution execution = run(PtiJob.OPS_RETENTION, "test-" + UUID.randomUUID());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM ops.etl_stream_batch WHERE listener_id = ?", Long.class, listener))
                .isZero();
        assertThat(execution.getStepExecutions().iterator().next().getCommitCount())
                .as("one transaction per 5,000 rows")
                .isGreaterThanOrEqualTo(24);
        assertThat(execution
                        .getStepExecutions()
                        .iterator()
                        .next()
                        .getExitStatus()
                        .getExitDescription())
                .contains("ops.etl_stream_batch: ");
    }

    @Test
    void l04MetadataCleanupKeepsFailedExecutionsAndCascades() {
        JobExecution completed = run(PtiJob.PARTITION_MAINTENANCE, uniqueDate());
        JobExecution failed = run(PtiJob.PARTITION_MAINTENANCE, uniqueDate());
        jdbc.update(
                "UPDATE batch.batch_job_execution SET status = 'FAILED', exit_code = 'FAILED' WHERE job_execution_id = ?",
                failed.getId());
        LocalDateTime old = LocalDateTime.now().minusDays(40);
        for (JobExecution e : List.of(completed, failed)) {
            jdbc.update(
                    "UPDATE batch.batch_job_execution SET create_time = ? WHERE job_execution_id = ?", old, e.getId());
        }

        JobExecution cleanup = run(PtiJob.BATCH_METADATA_CLEANUP, uniqueDate());

        assertThat(cleanup.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(executionExists(completed.getId())).isFalse();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM ops.etl_batch_step WHERE job_execution_id = ?",
                        Long.class,
                        completed.getId()))
                .as("etl_batch_step follows its step executions")
                .isZero();
        assertThat(executionExists(failed.getId())).isTrue();
    }

    private boolean executionExists(long id) {
        return jdbc.queryForObject(
                        "SELECT count(*) FROM batch.batch_job_execution WHERE job_execution_id = ?", Long.class, id)
                > 0;
    }

    @Test
    void l05DedupCleanupDeletesRowsOlderThanTheTtl() {
        String oldHash = WarehouseHashes.random();
        String newHash = WarehouseHashes.random();
        jdbc.update("""
                INSERT INTO ops.dedup_registry (source, payload_hash, first_seen_at, batch_id)
                VALUES ('TICKETING_SALES', ?, now() - interval '2 hours', gen_random_uuid()),
                       ('TICKETING_SALES', ?, now(), gen_random_uuid())
                """, oldHash, newHash);

        JobExecution execution =
                run(PtiJob.DEDUP_REGISTRY_CLEANUP, Instant.now().toString());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(jdbc.queryForList(
                        "SELECT payload_hash FROM ops.dedup_registry WHERE payload_hash IN (?, ?)",
                        String.class,
                        oldHash,
                        newHash))
                .containsExactly(newHash);
    }

    @Test
    void b08TwoTriggersOfTheSameInstanceStartOneExecution() {
        String runDate = uniqueDate();
        JobParameters parameters =
                JobParams.identity(PtiJob.PARTITION_MAINTENANCE, runDate).toJobParameters();
        CompletableFuture<?> a =
                CompletableFuture.runAsync(() -> launcher.launchIfNew(PtiJob.PARTITION_MAINTENANCE, parameters));
        CompletableFuture<?> b =
                CompletableFuture.runAsync(() -> launcher.launchIfNew(PtiJob.PARTITION_MAINTENANCE, parameters));
        CompletableFuture.allOf(a, b).join();

        await().atMost(Duration.ofSeconds(30))
                .until(() -> executionsOf(runDate).stream()
                        .noneMatch(status -> status.equals("STARTING") || status.equals("STARTED")));
        assertThat(executionsOf(runDate)).containsExactly("COMPLETED");
    }

    private List<String> executionsOf(String runDate) {
        return jdbc.queryForList("""
                SELECT je.status FROM batch.batch_job_execution je
                JOIN batch.batch_job_execution_params p USING (job_execution_id)
                WHERE p.parameter_name = 'runDate' AND p.parameter_value = ?
                """, String.class, runDate);
    }

    @Test
    void b09AStaleExecutionIsMarkedFailedAndRestarted() {
        String runDate = uniqueDate();
        JobExecution done = run(PtiJob.PARTITION_MAINTENANCE, runDate);
        long stepId = done.getStepExecutions().iterator().next().getId();
        LocalDateTime threeMinutesAgo = LocalDateTime.now().minusMinutes(3);
        jdbc.update("""
                UPDATE batch.batch_job_execution SET status = 'STARTED', exit_code = 'UNKNOWN', end_time = NULL,
                    last_updated = ? WHERE job_execution_id = ?
                """, threeMinutesAgo, done.getId());
        jdbc.update("""
                UPDATE batch.batch_step_execution SET status = 'STARTED', exit_code = 'EXECUTING', end_time = NULL,
                    last_updated = ? WHERE step_execution_id = ?
                """, threeMinutesAgo, stepId);

        List<JobExecution> recovered = recoverer.recover();

        assertThat(recovered).extracting(JobExecution::getId).contains(done.getId());
        JobExecution stale = jobRepository.getJobExecution(done.getId());
        assertThat(stale.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(stale.getExitStatus().getExitCode()).isEqualTo("STALE");
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () -> assertThat(executionsOf(runDate)).containsExactlyInAnyOrder("FAILED", "COMPLETED"));
    }

    /** The api writes job requests as {@code replay_operator} (DOC-17); {@code etl_writer} cannot insert them. */
    private static JdbcTemplate api;

    @BeforeAll
    static void openApi() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                MigratedDatabases.jdbcUrl("pti_warehouse"),
                "replay_operator",
                MigratedDatabases.password("replay_operator"),
                true);
        api = new JdbcTemplate(dataSource);
    }

    private UUID jobRequest(String kind, String jobName, String parameters, Long target) {
        UUID id = UUID.randomUUID();
        api.update("""
                INSERT INTO ops.job_request (id, kind, job_name, job_parameters, target_job_execution_id, requested_by)
                VALUES (?, ?, ?, ?::jsonb, ?, 'user:test')
                """, id, kind, jobName, parameters, target);
        return id;
    }

    private Map<String, Object> request(UUID id) {
        return jdbc.queryForMap("SELECT status, message, job_execution_id FROM ops.job_request WHERE id = ?", id);
    }

    private void awaitStatus(UUID id, String... statuses) {
        await().atMost(Duration.ofSeconds(30))
                .until(() -> List.of(statuses).contains((String) request(id).get("status")));
    }

    @Test
    void b12ARunRequestSeenByTwoPollersStartsOneExecution() {
        String runDate = uniqueDate();
        UUID id = jobRequest("RUN", "PartitionMaintenanceJob", "{\"runDate\": \"" + runDate + "\"}", null);
        CompletableFuture<?> a = CompletableFuture.runAsync(poller::poll);
        CompletableFuture<?> b = CompletableFuture.runAsync(poller::poll);
        CompletableFuture.allOf(a, b).join();

        awaitStatus(id, "DONE", "FAILED", "REJECTED");
        assertThat(request(id)).containsEntry("status", "DONE");
        assertThat((String) request(id).get("message")).startsWith("COMPLETED: Business date");
        assertThat(executionsOf(runDate)).containsExactly("COMPLETED");
    }

    @Test
    void b13RestartingACompletedExecutionIsRejected() {
        JobExecution completed = run(PtiJob.PARTITION_MAINTENANCE, uniqueDate());
        UUID id = jobRequest("RESTART", "PartitionMaintenanceJob", "{}", completed.getId());

        awaitStatus(id, "DONE", "FAILED", "REJECTED");
        assertThat(request(id)).containsEntry("status", "REJECTED");
        assertThat((String) request(id).get("message")).contains("COMPLETED, not FAILED or STOPPED");
    }

    @Test
    void aRequestWithAnUnknownJobOrParameterIsRejected() {
        UUID unknownJob = jobRequest("RUN", "NoSuchJob", "{}", null);
        UUID badParameter = jobRequest("RUN", "OpsRetentionJob", "{\"force\": true}", null);
        UUID badDate = jobRequest("RUN", "OpsRetentionJob", "{\"runDate\": \"tomorrow\"}", null);
        UUID replay = jobRequest("RUN", "DlqReplayJob", "{}", null);

        for (UUID id : List.of(unknownJob, badParameter, badDate, replay)) {
            awaitStatus(id, "DONE", "FAILED", "REJECTED");
            assertThat(request(id)).containsEntry("status", "REJECTED");
        }
        assertThat((String) request(badParameter).get("message")).contains("Parameter force is not allowed");
        assertThat((String) request(badDate).get("message")).contains("not a valid run_date");
    }

    @Test
    void aRestartRequestResumesAFailedExecution() {
        String runDate = uniqueDate();
        JobExecution done = run(PtiJob.PARTITION_MAINTENANCE, runDate);
        jdbc.update(
                "UPDATE batch.batch_job_execution SET status = 'FAILED', exit_code = 'FAILED' WHERE job_execution_id = ?",
                done.getId());
        UUID id = jobRequest("RESTART", "PartitionMaintenanceJob", "{}", done.getId());

        awaitStatus(id, "DONE", "FAILED", "REJECTED");
        Map<String, Object> row = request(id);
        assertThat(row).containsEntry("status", "DONE");
        assertThat((Long) row.get("job_execution_id")).isNotEqualTo(done.getId());
    }

    @Test
    void aStopRequestForAnExecutionThatIsNotRunningIsRejected() {
        JobExecution completed = run(PtiJob.PARTITION_MAINTENANCE, uniqueDate());
        UUID id = jobRequest("STOP", "PartitionMaintenanceJob", "{}", completed.getId());

        awaitStatus(id, "DONE", "FAILED", "REJECTED");
        assertThat(request(id)).containsEntry("status", "REJECTED");
    }

    /** Payload hashes for the dedup registry: 64 hex characters. */
    static final class WarehouseHashes {
        static String random() {
            return dev.pti.etl.WarehouseSupport.hash(UUID.randomUUID().toString());
        }
    }
}
