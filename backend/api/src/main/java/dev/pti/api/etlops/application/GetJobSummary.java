package dev.pti.api.etlops.application;

import dev.pti.api.etlops.application.port.JobRunReader;
import dev.pti.api.etlops.domain.JobSummary;
import dev.pti.api.etlops.domain.SummaryBucket;
import dev.pti.api.platform.domain.ValidationException;
import dev.pti.common.tx.TransactionRunner;
import java.time.Instant;

/**
 * {@code GET /etl/jobs/summary} (DOC-32 E-31): the timeline of micro-batches per source and the count of batch jobs by
 * outcome. A range with more buckets than a chart needs is refused with a hint to take a larger bucket.
 */
public final class GetJobSummary {

    private final JobRunReader runs;
    private final TransactionRunner tx;

    public GetJobSummary(JobRunReader runs, TransactionRunner tx) {
        this.runs = runs;
        this.tx = tx;
    }

    /** @throws ValidationException on {@code bucket} when buckets times sources exceed 1,440 x 4 */
    public JobSummary execute(Instant from, Instant to, SummaryBucket bucket) {
        if (JobSummary.pointCount(bucket, from, to) > JobSummary.MAX_POINTS) {
            throw ValidationException.of("bucket", "is too small for this range; use a larger bucket");
        }
        return tx.inTransaction(() ->
                JobSummary.of(bucket, from, to, runs.streamSummary(from, to, bucket), runs.batchJobStatuses(from, to)));
    }
}
