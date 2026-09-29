package dev.pti.etl.batch;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;

/** Job parameters of DOC-19 §2. All are strings, so that the ops console shows them as they were given. */
public final class JobParams {

    /** Non-identifying parameter linking an execution to the {@code ops.job_request} that started it. */
    public static final String JOB_REQUEST_ID = "jobRequestId";

    private JobParams() {}

    public static JobParametersBuilder identity(PtiJob job, String value) {
        return new JobParametersBuilder().addString(job.identity().parameter(), value, true);
    }

    public static JobParameters runDate(PtiJob job, LocalDate date) {
        return identity(job, date.toString()).toJobParameters();
    }

    /** The slot that contains {@code now}, e.g. {@code 2026-09-29T21:15:00Z} for a 15-minute schedule. */
    public static JobParameters slot(PtiJob job, Instant now, int minutes) {
        long slotSeconds = minutes * 60L;
        Instant start = Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(), slotSeconds) * slotSeconds);
        return identity(job, start.truncatedTo(ChronoUnit.SECONDS).toString()).toJobParameters();
    }

    public static JobParameters runKey(PtiJob job, String runKey) {
        return identity(job, runKey).toJobParameters();
    }

    public static JobParameters replay(PtiJob job, UUID replayRequestId) {
        return identity(job, replayRequestId.toString())
                .addString(StepValues.REPLAY, "true", false)
                .toJobParameters();
    }
}
