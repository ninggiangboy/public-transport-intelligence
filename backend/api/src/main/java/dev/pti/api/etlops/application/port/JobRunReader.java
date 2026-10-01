package dev.pti.api.etlops.application.port;

import dev.pti.api.etlops.domain.JobParameter;
import dev.pti.api.etlops.domain.JobRun;
import dev.pti.api.etlops.domain.JobRunFilter;
import dev.pti.api.etlops.domain.JobSummary;
import dev.pti.api.etlops.domain.RequestRef;
import dev.pti.api.etlops.domain.RunId;
import dev.pti.api.etlops.domain.StepRun;
import dev.pti.api.etlops.domain.StreamBatch;
import dev.pti.api.etlops.domain.SummaryBucket;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Reads the runs of {@code ops.ops_job_run_v} and what hangs off them (DOC-32 E-30…E-32), as {@code api_reader}. */
public interface JobRunReader {

    /** The runs started in the range, newest first, by {@code (started_at, run_id)} descending. */
    Page<JobRun> list(JobRunFilter filter, PageRequest page);

    Optional<JobRun> find(RunId id);

    List<JobParameter> parameters(long jobExecutionId);

    List<StepRun> steps(long jobExecutionId);

    /** The replay or job request that started the execution, a replay first. */
    Optional<RequestRef> requestOf(long jobExecutionId);

    /** The micro-batches of a listener in one minute, oldest first, at most {@code limit}. */
    List<StreamBatch> streamBatches(String listenerId, Instant minute, int limit);

    /** The micro-batch buckets of the range per source, only those that have a batch. */
    List<JobSummary.Row> streamSummary(Instant from, Instant to, SummaryBucket bucket);

    /** The batch job executions started in the range, counted by Spring Batch status. */
    Map<String, Integer> batchJobStatuses(Instant from, Instant to);
}
