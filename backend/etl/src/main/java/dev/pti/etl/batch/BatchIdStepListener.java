package dev.pti.etl.batch;

import com.github.f4b6a3.uuid.UuidCreator;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Gives every step execution its own {@code batch_id} (DR-63, DOC-19 §4.6): a restart creates a new step execution,
 * so rows written after the restart carry a new id while committed rows keep the old one (test B-15).
 */
public class BatchIdStepListener implements StepExecutionListener {

    static final String MDC_BATCH_ID = "batch_id";

    private final JdbcTemplate jdbc;

    public BatchIdStepListener(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void beforeStep(StepExecution step) {
        UUID batchId = UuidCreator.getTimeOrderedEpoch();
        jdbc.update(
                """
                INSERT INTO ops.etl_batch_step (batch_id, job_execution_id, step_execution_id, job_name, step_name)
                VALUES (?, ?, ?, ?, ?)
                """,
                batchId,
                step.getJobExecutionId(),
                step.getId(),
                step.getJobExecution().getJobInstance().getJobName(),
                step.getStepName());
        step.getExecutionContext().putString(StepValues.BATCH_ID, batchId.toString());
        MDC.put(MDC_BATCH_ID, batchId.toString());
    }

    @Override
    public ExitStatus afterStep(StepExecution step) {
        MDC.remove(MDC_BATCH_ID);
        return step.getExitStatus();
    }
}
