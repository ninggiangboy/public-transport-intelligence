package dev.pti.analytics.recompute.application;

import dev.pti.analytics.core.domain.Detector;
import dev.pti.analytics.recompute.domain.DetectorStats;
import dev.pti.analytics.recompute.domain.ReplayRange;
import dev.pti.analytics.recompute.domain.WorkItem;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** How one detector recomputes: which items a range needs and how an item runs (DOC-23 §11.1). */
public interface DetectorRecompute {

    Detector detector();

    /** The items for {@code [from, to]} of event time. Empty when there is no feed or the detector is off. */
    List<WorkItem> plan(Instant from, Instant to);

    /**
     * The items for the records of a replay. The range a detector reads is its own: disruption looks back one window
     * further than the replayed records.
     */
    default List<WorkItem> planReplay(ReplayRange range) {
        return plan(range.minEventTs(), range.maxEventTs());
    }

    /** Runs one item of this detector in one transaction (the caller's, when there is one). */
    DetectorStats execute(WorkItem item, UUID batchId);
}
