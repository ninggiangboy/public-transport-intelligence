package dev.pti.etl.gtfs;

import java.time.ZoneId;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.infrastructure.item.ExecutionContext;

/** Job execution context keys of {@code GtfsStaticLoadJob} (DOC-19 §3.2, DOC-21 §3.1 step 8). */
public final class FeedContext {

    public static final String FEED_VERSION_ID = "pti.feedVersionId";
    public static final String FEED_HASH = "pti.feedHash";
    public static final String AGENCY_ZONE = "pti.agencyZone";
    /** GV-01…GV-03 and GV-14 found by {@code fetch}, and {@code extra_columns}, as JSON text. */
    public static final String FETCH_ISSUES = "pti.gtfs.fetchIssues";

    public static final String EXTRA_COLUMNS = "pti.gtfs.extraColumns";
    /** Row errors of one load step (GV-04), as JSON text: {@code pti.gtfs.rowIssues.<step>}. */
    public static final String ROW_ISSUES_PREFIX = "pti.gtfs.rowIssues.";

    private FeedContext() {}

    public static ExecutionContext of(JobExecution job) {
        return job.getExecutionContext();
    }

    /** The job context of the step running on this thread. */
    public static JobExecution currentJob() {
        StepContext context = StepSynchronizationManager.getContext();
        if (context == null) {
            throw new IllegalStateException("No step is running on this thread");
        }
        return context.getStepExecution().getJobExecution();
    }

    public static long feedVersionId(JobExecution job) {
        return job.getExecutionContext().getLong(FEED_VERSION_ID);
    }

    public static String feedHash(JobExecution job) {
        return job.getExecutionContext().getString(FEED_HASH);
    }

    public static ZoneId agencyZone(JobExecution job) {
        return ZoneId.of(job.getExecutionContext().getString(AGENCY_ZONE));
    }
}
