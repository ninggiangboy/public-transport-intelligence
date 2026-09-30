package dev.pti.analytics.core.domain;

import com.github.f4b6a3.uuid.UuidCreator;
import dev.pti.analytics.event.domain.InsightEvent;
import java.util.List;
import java.util.UUID;

/**
 * Result of one unit of analytics work (DOC-23 §3). Events are published by the caller after the commit.
 *
 * @param scope the route, date or window the unit worked on, for logs
 * @param batchId the id written to the {@code batch_id} column and logged with the run (DOC-23 §2.3)
 * @param gridPoints how many grid points the run evaluated
 */
public record RunResult(
        Detector detector,
        String scope,
        Trigger trigger,
        Outcome outcome,
        UUID batchId,
        int gridPoints,
        int opened,
        int updated,
        int closed,
        int deleted,
        List<InsightEvent> events) {

    public RunResult {
        events = List.copyOf(events);
    }

    /** A fresh UUIDv7: the {@code batch_id} of one live {@code advance} (DOC-23 §2.3). */
    public static UUID newBatchId() {
        return UuidCreator.getTimeOrderedEpoch();
    }

    /** Nothing to do: the grid is already evaluated up to the watermark. */
    public static RunResult noop(Detector detector, String scope, Trigger trigger, UUID batchId) {
        return empty(detector, scope, trigger, Outcome.NOOP, batchId);
    }

    /** The advisory lock is held elsewhere; the next trigger continues from the cursor (DOC-23 §2.5). */
    public static RunResult skippedLocked(Detector detector, String scope, Trigger trigger, UUID batchId) {
        return empty(detector, scope, trigger, Outcome.SKIPPED_LOCKED, batchId);
    }

    /** The unit failed and rolled back; the next trigger starts again from the cursor (DOC-23 §15). */
    public static RunResult error(Detector detector, String scope, Trigger trigger, UUID batchId) {
        return empty(detector, scope, trigger, Outcome.ERROR, batchId);
    }

    /** True when the run changed a row, which decides between {@code DEBUG} and {@code INFO} in the log. */
    public boolean changedAnything() {
        return opened > 0 || updated > 0 || closed > 0 || deleted > 0;
    }

    private static RunResult empty(Detector detector, String scope, Trigger trigger, Outcome outcome, UUID batchId) {
        return new RunResult(detector, scope, trigger, outcome, batchId, 0, 0, 0, 0, 0, List.of());
    }
}
