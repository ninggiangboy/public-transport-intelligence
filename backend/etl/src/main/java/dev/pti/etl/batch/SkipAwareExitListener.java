package dev.pti.etl.batch;

import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;

/** {@code COMPLETED_WITH_SKIPS} when at least one item went to the dead-letter queue (DOC-19 §5.1). */
public class SkipAwareExitListener implements StepExecutionListener {

    public static final String COMPLETED_WITH_SKIPS = "COMPLETED_WITH_SKIPS";

    @Override
    public ExitStatus afterStep(StepExecution step) {
        ExitStatus exit = step.getExitStatus();
        long skipped = step.getSkipCount() + StepValues.rejected(step);
        if (skipped > 0 && ExitStatus.COMPLETED.getExitCode().equals(exit.getExitCode())) {
            return new ExitStatus(COMPLETED_WITH_SKIPS, skipped + " item(s) sent to the dead-letter queue");
        }
        return exit;
    }
}
