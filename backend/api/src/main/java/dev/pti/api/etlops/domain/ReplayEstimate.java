package dev.pti.api.etlops.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The estimate before an operator starts a raw zone replay (DOC-32 E-53). The API cannot list the raw zone (TB-2), so
 * the estimate comes from the log of micro-batches: how many records they read in the window.
 */
public record ReplayEstimate(
        String source,
        Instant fromTs,
        Instant toTs,
        @Nullable Long estimatedMessages,
        @Nullable Long estimatedDurationSeconds,
        String basis,
        double coverage,
        List<String> warnings) {

    public static final String STREAM_BATCH_LOG = "STREAM_BATCH_LOG";
    public static final String UNAVAILABLE = "UNAVAILABLE";

    /** The micro-batch log of a window: the records read and how many of its minutes have a batch. */
    public record History(long recordsRead, long batches, long minutesWithBatches) {}

    /** A micro-batch processes a record a few seconds after the raw zone got it, so the log is read this much later. */
    public static final Duration LOOKAHEAD = Duration.ofMinutes(5);

    public ReplayEstimate {
        warnings = List.copyOf(warnings);
    }

    /**
     * @param throughput messages per second the replay is expected to do ({@code pti.api.replay-estimate.throughput})
     */
    public static ReplayEstimate of(
            String source, Instant fromTs, Instant toTs, History history, int throughput, boolean rawReplayActive) {
        List<String> warnings = new ArrayList<>();
        ReplayEstimate estimate;
        if (history.batches() == 0) {
            warnings.add("No processing history for this range.");
            estimate = new ReplayEstimate(source, fromTs, toTs, null, null, UNAVAILABLE, 0.0, warnings);
        } else {
            long minutes =
                    Math.max(1, (long) Math.ceil(Duration.between(fromTs, toTs).toSeconds() / 60.0));
            double coverage = Math.min(1.0, (double) history.minutesWithBatches() / minutes);
            if (coverage < 0.9) {
                warnings.add("Some minutes in this range have no processing history; the estimate may be low.");
            }
            long seconds = (long) Math.ceil((double) history.recordsRead() / throughput);
            estimate = new ReplayEstimate(
                    source, fromTs, toTs, history.recordsRead(), seconds, STREAM_BATCH_LOG, coverage, warnings);
        }
        if (rawReplayActive) {
            List<String> all = new ArrayList<>(estimate.warnings());
            all.add("A replay for this source is already running.");
            return new ReplayEstimate(
                    source,
                    fromTs,
                    toTs,
                    estimate.estimatedMessages(),
                    estimate.estimatedDurationSeconds(),
                    estimate.basis(),
                    estimate.coverage(),
                    all);
        }
        return estimate;
    }
}
