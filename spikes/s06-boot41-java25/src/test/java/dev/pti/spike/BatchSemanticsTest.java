package dev.pti.spike;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.spike.batch.FaultPlan;
import dev.pti.spike.batch.Item;
import dev.pti.spike.batch.SpikeJobs;
import dev.pti.spike.batch.SpikeJobs.Variant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/** S-06 point 0 (DR-53): chunk semantics of Spring Batch 6 on a real Postgres. */
@SpringBootTest
@Import(TestPostgres.class)
class BatchSemanticsTest {

    @Autowired
    SpikeJobs jobs;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    JobRepository jobRepository;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE spike_fact, spike_dlq");
    }

    static List<Item> items(int count, int... badIds) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(id -> new Item(id, IntStream.of(badIds).anyMatch(b -> b == id) ? -1 : id))
                .toList();
    }

    JobExecution start(Job job) throws Exception {
        return jobOperator.start(
                job,
                new JobParametersBuilder().addLong("run", System.nanoTime()).toJobParameters());
    }

    /**
     * JobOperator.restart(JobExecution) looks the job up in the JobRegistry; jobs built in a test are not registered,
     * so restart the same way JobLauncher did: start again with the same identifying parameters.
     */
    JobExecution restart(Job job, JobExecution previous) throws Exception {
        return jobOperator.start(job, previous.getJobParameters());
    }

    int facts() {
        return jdbc.queryForObject("SELECT count(*) FROM spike_fact", Integer.class);
    }

    int deadLetters() {
        return jdbc.queryForObject("SELECT count(*) FROM spike_dlq", Integer.class);
    }

    /** The acceptance test named in DR-53: write error at item 37 -> 499 rows, 1 dead letter. */
    @ParameterizedTest
    @EnumSource(Variant.class)
    void skipAtWriteKeepsDeadLetter(Variant variant) throws Exception {
        JobExecution execution = start(jobs.job("skip-" + variant, variant, items(500, 37), 500, new FaultPlan()));

        StepExecution step = execution.getStepExecutions().iterator().next();
        System.out.printf(
                "[S-06] %s skipAtWrite: status=%s facts=%d dlq=%d writeSkip=%d commits=%d rollbacks=%d dlqTxActive=%s%n",
                variant,
                execution.getStatus(),
                facts(),
                deadLetters(),
                step.getWriteSkipCount(),
                step.getCommitCount(),
                step.getRollbackCount(),
                jdbc.queryForList("SELECT tx_active FROM spike_dlq", Boolean.class));
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(facts()).isEqualTo(499);
        if (variant == Variant.NEW) {
            // Finding (Batch 6.0.5): the scan transaction of the skipped item is rolled back after onSkipInWrite,
            // so the dead letter and the write-skip count are lost.
            assertThat(deadLetters()).isZero();
            assertThat(step.getWriteSkipCount()).isZero();
            return;
        }
        assertThat(step.getWriteSkipCount()).isEqualTo(1);
        assertThat(deadLetters())
                .as("dead letter written by the skip listener survives")
                .isEqualTo(1);
    }

    /** Restart after a non-skippable error resumes from the last committed chunk. */
    @ParameterizedTest
    @EnumSource(Variant.class)
    void restartResumesFromLastCommit(Variant variant) throws Exception {
        FaultPlan faults = new FaultPlan();
        faults.fatalOnceAt(700);
        Job job = jobs.job("restart-" + variant, variant, items(1200), 500, faults);
        JobExecution first = start(job);
        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        int afterFirst = facts();

        JobExecution second = restart(job, first);
        StepExecution step = second.getStepExecutions().iterator().next();
        System.out.printf(
                "[S-06] %s restart: firstFacts=%d secondStatus=%s secondRead=%d facts=%d%n",
                variant, afterFirst, second.getStatus(), step.getReadCount(), facts());
        assertThat(afterFirst).isEqualTo(500);
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(step.getReadCount()).as("reads only what was not committed").isEqualTo(700);
        assertThat(facts()).isEqualTo(1200);
    }

    /** A crash in the middle of a scan must not lose the items of the chunk that were not yet scanned. */
    @ParameterizedTest
    @EnumSource(Variant.class)
    void crashDuringScanThenRestart(Variant variant) throws Exception {
        FaultPlan faults = new FaultPlan();
        faults.crashOnceDuringScanAt(300);
        Job job = jobs.job("crash-" + variant, variant, items(500, 37), 500, faults);
        JobExecution first;
        try {
            first = start(job);
        } catch (FaultPlan.SimulatedCrash e) {
            first = null;
        }
        JobExecution crashed =
                first != null ? first : jobRepository.getLastJobExecution("crash-" + variant, lastParams(variant));
        System.out.printf(
                "[S-06] %s crashDuringScan: firstStatus=%s factsAfterCrash=%d%n",
                variant, crashed.getStatus(), facts());
        if (crashed.getStatus().isRunning()) {
            crashed = jobOperator.recover(crashed);
        }
        JobExecution second = restart(job, crashed);
        System.out.printf(
                "[S-06] %s crashDuringScan: restartStatus=%s facts=%d dlq=%d%n",
                variant, second.getStatus(), facts(), deadLetters());
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        if (variant == Variant.NEW) {
            // Finding (Batch 6.0.5): each scan transaction saves the reader position of the whole chunk,
            // so a crash mid-scan followed by a restart silently skips the items not yet scanned.
            assertThat(facts()).isLessThan(499);
            return;
        }
        assertThat(facts()).as("no good item lost across crash + restart").isEqualTo(499);
    }

    /** DR-24 point (d): recover a STARTED execution left by a dead pod, fence the old owner, restart. */
    @ParameterizedTest
    @EnumSource(Variant.class)
    void recoverStaleExecutionAndFence(Variant variant) throws Exception {
        FaultPlan faults = new FaultPlan();
        faults.fatalOnceAt(700);
        Job job = jobs.job("stale-" + variant, variant, items(1200), 500, faults);
        JobExecution failed = start(job);
        long stepId = failed.getStepExecutions().iterator().next().getId();
        // Simulate a pod killed mid-run: the metadata still says STARTED.
        jdbc.update(
                "UPDATE batch.BATCH_JOB_EXECUTION SET STATUS = 'STARTED', END_TIME = NULL WHERE JOB_EXECUTION_ID = ?",
                failed.getId());
        jdbc.update(
                "UPDATE batch.BATCH_STEP_EXECUTION SET STATUS = 'STARTED', END_TIME = NULL WHERE STEP_EXECUTION_ID = ?",
                stepId);
        // What the dead pod still holds in memory: its own copy with the current VERSION.
        StepExecution oldOwnersView = jobRepository.getStepExecution(stepId);
        long versionBefore = versionOf(stepId);

        JobExecution recovered = jobOperator.recover(jobRepository.getJobExecution(failed.getId()));
        long versionAfter = versionOf(stepId);
        System.out.printf(
                "[S-06] %s recover: status=%s exitCode=%s stepStatus=%s dbVersion %d -> %d%n",
                variant,
                recovered.getStatus(),
                recovered.getExitStatus().getExitCode(),
                jdbc.queryForObject(
                        "SELECT STATUS FROM batch.BATCH_STEP_EXECUTION WHERE STEP_EXECUTION_ID = ?",
                        String.class,
                        stepId),
                versionBefore,
                versionAfter);
        assertThat(recovered.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(versionAfter).isGreaterThan(versionBefore);

        oldOwnersView.setStatus(BatchStatus.COMPLETED);
        assertThatThrownBy(() -> jobRepository.update(oldOwnersView))
                .isInstanceOf(OptimisticLockingFailureException.class);

        JobExecution restarted = restart(job, recovered);
        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(facts()).isEqualTo(1200);
    }

    long versionOf(long stepId) {
        return jdbc.queryForObject(
                "SELECT VERSION FROM batch.BATCH_STEP_EXECUTION WHERE STEP_EXECUTION_ID = ?", Long.class, stepId);
    }

    /** DOC-19 §4.4: a transient write error is retried on the legacy fault-tolerant step. */
    @org.junit.jupiter.api.Test
    void transientErrorIsRetried() throws Exception {
        FaultPlan faults = new FaultPlan();
        faults.transientOnceAt(250);
        JobExecution execution = start(jobs.job("transient", Variant.LEGACY, items(500), 500, faults));
        StepExecution step = execution.getStepExecutions().iterator().next();
        System.out.printf(
                "[S-06] LEGACY transient: status=%s facts=%d rollbacks=%d dlq=%d%n",
                execution.getStatus(), facts(), step.getRollbackCount(), deadLetters());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(facts()).isEqualTo(500);
        assertThat(deadLetters()).isZero();
    }

    /** DR-62: the step execution context is stored as JSON. */
    @org.junit.jupiter.api.Test
    void executionContextIsJson() throws Exception {
        start(jobs.job("json-ctx", Variant.NEW, items(10), 5, new FaultPlan()));
        String ctx = jdbc.queryForObject(
                "SELECT c.SHORT_CONTEXT FROM batch.BATCH_STEP_EXECUTION_CONTEXT c ORDER BY STEP_EXECUTION_ID DESC LIMIT 1",
                String.class);
        System.out.println("[S-06] step context: " + ctx);
        assertThat(ctx).startsWith("{").contains("items.read.count");
    }

    private org.springframework.batch.core.job.parameters.JobParameters lastParams(Variant variant) {
        return jobRepository.getJobInstances("crash-" + variant, 0, 1).stream()
                .findFirst()
                .map(i -> jobRepository.getJobExecutions(i).getFirst().getJobParameters())
                .orElseThrow();
    }
}
