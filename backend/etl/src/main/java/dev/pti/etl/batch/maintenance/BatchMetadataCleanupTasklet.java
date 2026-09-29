package dev.pti.etl.batch.maintenance;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Deletes Spring Batch metadata older than the retention (DR-62, DOC-18 §5), a batch of job instances per
 * transaction. An instance goes only when its newest execution ended {@code COMPLETED} or {@code ABANDONED} and none
 * of its executions is recent, so a {@code FAILED} execution that nobody restarted stays for diagnosis (test L-04).
 * Tables are cleared children first; {@code ops.etl_batch_step} follows its step executions by cascade.
 */
public class BatchMetadataCleanupTasklet implements Tasklet {

    static final String DELETED_KEY = "pti.purge.deleted.batch_job_instance";

    private static final String SELECT_INSTANCES = """
            SELECT ji.job_instance_id
            FROM batch.batch_job_instance ji
            WHERE NOT EXISTS (SELECT 1 FROM batch.batch_job_execution je
                              WHERE je.job_instance_id = ji.job_instance_id AND je.create_time >= ?)
              AND (SELECT je.status FROM batch.batch_job_execution je
                   WHERE je.job_instance_id = ji.job_instance_id
                   ORDER BY je.job_execution_id DESC LIMIT 1) IN ('COMPLETED', 'ABANDONED')
            ORDER BY ji.job_instance_id
            LIMIT ?
            """;

    private static final String EXECUTIONS =
            "SELECT job_execution_id FROM batch.batch_job_execution WHERE job_instance_id = ANY (?::bigint[])";

    private static final List<String> DELETES = List.of(
            "DELETE FROM batch.batch_step_execution_context WHERE step_execution_id IN "
                    + "(SELECT step_execution_id FROM batch.batch_step_execution WHERE job_execution_id IN ("
                    + EXECUTIONS + "))",
            "DELETE FROM batch.batch_step_execution WHERE job_execution_id IN (" + EXECUTIONS + ")",
            "DELETE FROM batch.batch_job_execution_context WHERE job_execution_id IN (" + EXECUTIONS + ")",
            "DELETE FROM batch.batch_job_execution_params WHERE job_execution_id IN (" + EXECUTIONS + ")",
            "DELETE FROM batch.batch_job_execution WHERE job_instance_id = ANY (?::bigint[])",
            "DELETE FROM batch.batch_job_instance WHERE job_instance_id = ANY (?::bigint[])");

    private final JdbcTemplate jdbc;
    private final Clock batchClock;
    private final Duration retention;
    private final int batchSize;
    private final RetentionMetrics metrics;

    /** @param batchClock the clock of Spring Batch's {@code LocalDateTime} columns (the JVM zone, UTC) */
    public BatchMetadataCleanupTasklet(
            JdbcTemplate jdbc, Clock batchClock, Duration retention, int batchSize, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.batchClock = batchClock;
        this.retention = retention;
        this.batchSize = batchSize;
        this.metrics = new RetentionMetrics(meters);
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        ExecutionContext context = contribution.getStepExecution().getExecutionContext();
        LocalDateTime cutoff = LocalDateTime.now(batchClock).minus(retention);
        List<Long> instances = jdbc.queryForList(SELECT_INSTANCES, Long.class, cutoff, batchSize);
        if (!instances.isEmpty()) {
            String ids = instances.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
            for (String delete : DELETES) {
                jdbc.update(delete, ids);
            }
            context.putLong(DELETED_KEY, context.getLong(DELETED_KEY, 0L) + instances.size());
            contribution.incrementWriteCount(instances.size());
            metrics.deleted("batch.batch_job_instance", instances.size());
        }
        if (instances.size() < batchSize) {
            contribution.setExitStatus(ExitStatus.COMPLETED.addExitDescription(
                    "Deleted " + context.getLong(DELETED_KEY, 0L) + " job instance(s) older than " + cutoff));
            return RepeatStatus.FINISHED;
        }
        return RepeatStatus.CONTINUABLE;
    }
}
