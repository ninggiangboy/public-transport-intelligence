package dev.pti.etl.batch;

import org.slf4j.MDC;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;

/** Puts the job name in the MDC and records the end of the execution on its {@code job_request} (DOC-19 §7.3). */
public class JobRequestListener implements JobExecutionListener {

    static final String MDC_JOB = "job";

    private final JobRequests requests;

    public JobRequestListener(JobRequests requests) {
        this.requests = requests;
    }

    @Override
    public void beforeJob(JobExecution execution) {
        MDC.put(MDC_JOB, execution.getJobInstance().getJobName());
    }

    @Override
    public void afterJob(JobExecution execution) {
        try {
            requests.finish(execution);
        } finally {
            MDC.remove(MDC_JOB);
        }
    }
}
