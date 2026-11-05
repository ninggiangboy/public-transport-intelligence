package dev.pti.etl.batch.adapter.in.batch;

import dev.pti.etl.batch.application.port.JobRequestStore;
import dev.pti.etl.batch.application.port.JobRequestStore.FinishedJob;
import org.slf4j.MDC;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;

/** Puts the job name in the MDC and records the end of the execution on its {@code job_request} (DOC-19 §7.3). */
public class JobRequestListener implements JobExecutionListener {

    static final String MDC_JOB = "job";

    private final JobRequestStore requests;

    public JobRequestListener(JobRequestStore requests) {
        this.requests = requests;
    }

    @Override
    public void beforeJob(JobExecution execution) {
        MDC.put(MDC_JOB, execution.getJobInstance().getJobName());
    }

    @Override
    public void afterJob(JobExecution execution) {
        try {
            requests.finish(finished(execution));
        } finally {
            MDC.remove(MDC_JOB);
        }
    }

    private static FinishedJob finished(JobExecution execution) {
        String description = execution.getExitStatus().getExitDescription();
        return new FinishedJob(
                execution.getStatus() == BatchStatus.COMPLETED,
                execution.getExitStatus().getExitCode(),
                description,
                execution.getId(),
                execution.getJobParameters().getString(JobParams.JOB_REQUEST_ID));
    }
}
