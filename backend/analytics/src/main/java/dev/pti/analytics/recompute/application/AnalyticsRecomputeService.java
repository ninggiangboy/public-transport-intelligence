package dev.pti.analytics.recompute.application;

import dev.pti.analytics.core.domain.Detector;
import dev.pti.analytics.core.domain.RunResult;
import dev.pti.analytics.recompute.domain.DetectorStats;
import dev.pti.analytics.recompute.domain.ReplayRange;
import dev.pti.analytics.recompute.domain.ReplaySource;
import dev.pti.analytics.recompute.domain.WorkItem;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Recomputes the analytics output of a range after a raw zone replay or on request (DOC-23 §11.1). A caller plans
 * first and then executes the items one at a time, each in its own transaction, so that a job step can commit after
 * each one and continue from there after a failure.
 */
public interface AnalyticsRecomputeService {

    /** Plan for {@code RawZoneReplayJob}: the range is that of the replayed records (DOC-23 §11.1, table). */
    List<WorkItem> plan(ReplaySource source, ReplayRange range);

    /** Plan for {@code AnalyticsRecomputeJob}: {@code [from, to]} in event time. */
    List<WorkItem> plan(Set<Detector> detectors, Instant from, Instant to);

    /**
     * Runs one item in one transaction, with a blocking lock (DOC-23 §2.5). The transaction is the caller's when there
     * is one, as for the other jobs.
     *
     * @param batchId the {@code batch_id} written to the rows: the step's (DR-63)
     */
    DetectorStats execute(WorkItem item, UUID batchId);

    /** As {@link #execute(WorkItem, UUID)} with a new {@code batch_id}. */
    default DetectorStats execute(WorkItem item) {
        return execute(item, RunResult.newBatchId());
    }
}
