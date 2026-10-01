package dev.pti.analytics.recompute.application;

import dev.pti.analytics.core.domain.Outcome;
import dev.pti.analytics.core.domain.RunResult;
import dev.pti.analytics.recompute.domain.DetectorStats;

/** Turns the {@link RunResult} of one item into its share of the statistics (DOC-23 §11.7). */
final class RunStats {

    private RunStats() {}

    /**
     * An item that ran counts as one scope. Its rows are the ones inserted ({@code opened}) and the ones updated; a
     * recompute that did nothing, because no feed is ACTIVE, counts for nothing.
     */
    static DetectorStats of(RunResult result) {
        if (result.outcome() != Outcome.OK) {
            return DetectorStats.NONE;
        }
        return new DetectorStats(1, result.opened() + result.updated(), result.deleted());
    }
}
