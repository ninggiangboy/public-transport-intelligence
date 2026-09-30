package dev.pti.analytics.disruption.domain;

import dev.pti.common.id.InsightIds;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The bucket processing of DOC-23 §6.3: an EWMA baseline per (route, direction), a z-score of each bucket against the
 * baseline before the bucket, hysteresis to open and close an episode, a warm-up before the first episode, and the
 * rules for buckets without enough samples. Pure: a bucket goes in with the state before it, the state after it comes
 * out, and nothing is read from the clock or the database. The live detector calls it once per bucket and the
 * recompute replays the same buckets from a snapshot through the same method (DOC-23 §11.4).
 */
public final class DisruptionStateMachine {

    /** Most stops an episode lists as affected (DOC-23 §6.3). */
    public static final int MAX_AFFECTED_STOPS = 100;

    private final DisruptionThresholds thresholds;
    private final double sigmaFloor;

    public DisruptionStateMachine(DisruptionThresholds thresholds) {
        this.thresholds = thresholds;
        this.sigmaFloor = thresholds.sigmaFloor().toMillis() / 1000.0;
    }

    /** The state after one bucket and what the bucket did to the episode, if anything. */
    public record Step(DirectionState state, @Nullable EpisodeChange change) {}

    /**
     * Processes the bucket that ends at {@code bucket.end()}.
     *
     * @param state the state after the bucket before this one
     */
    public Step process(String routeId, int directionId, DirectionState state, BucketObservation bucket) {
        BaselineState baseline = state.baseline();
        DisruptionEpisode episode = state.episode();
        Instant end = bucket.end();
        if (bucket.sampleCount() < thresholds.minSamples()) {
            return noData(baseline, episode, end);
        }
        double x = bucket.meanDelay();
        if (baseline.bucketCount() == 0) {
            // The first bucket with data initialises the baseline; there is nothing to compare it with.
            return quiet(new BaselineState(
                    x, 0, 1, end, baseline.consecutiveHigh(), baseline.consecutiveLow(), baseline.openEpisodeId()));
        }
        double z = zScore(x, baseline);
        if (episode != null) {
            return whileOpen(baseline, episode, bucket, z);
        }
        if (baseline.bucketCount() >= thresholds.warmUpBuckets() && z > thresholds.openZ()) {
            return high(routeId, directionId, baseline, bucket, z);
        }
        return quiet(updated(baseline, x, end));
    }

    /** {@code z = (x − μ) / max(σ, sigma-floor)}, against the baseline before the bucket's own update. */
    public double zScore(double x, BaselineState baseline) {
        return (x - baseline.mean()) / Math.max(baseline.sigma(), sigmaFloor);
    }

    /**
     * A bucket with too few arrivals does not touch the baseline and breaks a run of high buckets. While an episode is
     * open it counts as a low one, so that a route that stops reporting still closes it.
     */
    private Step noData(BaselineState baseline, @Nullable DisruptionEpisode episode, Instant end) {
        if (episode == null) {
            return quiet(counters(baseline, end, 0, baseline.consecutiveLow(), baseline.openEpisodeId()));
        }
        int low = baseline.consecutiveLow() + 1;
        if (low >= thresholds.closeConsecutive()) {
            DisruptionEpisode closed = episode.closed(end, CloseReason.NO_DATA);
            return closed(counters(baseline, end, 0, 0, null), closed);
        }
        return new Step(new DirectionState(counters(baseline, end, 0, low, episode.id()), episode), null);
    }

    /** The baseline is frozen while an episode is open, also in the bucket that closes it. */
    private Step whileOpen(BaselineState baseline, DisruptionEpisode episode, BucketObservation bucket, double z) {
        Instant end = bucket.end();
        DisruptionEpisode observed = episode.observed(bucket, z, stopsAbove(bucket, threshold(episode)));
        int low = z < thresholds.closeZ() ? baseline.consecutiveLow() + 1 : 0;
        if (low >= thresholds.closeConsecutive()) {
            return closed(counters(baseline, end, 0, 0, null), observed.closed(end, CloseReason.RECOVERED));
        }
        if (!observed.start().plus(thresholds.maxEpisodeDuration()).isAfter(end)) {
            // The new level of delay has become normal: close, and re-seed the mean; the variance and the warm-up
            // counter stay.
            BaselineState reseeded =
                    new BaselineState(bucket.meanDelay(), baseline.variance(), baseline.bucketCount(), end, 0, 0, null);
            return closed(reseeded, observed.closed(end, CloseReason.MAX_DURATION));
        }
        return new Step(
                new DirectionState(counters(baseline, end, 0, low, episode.id()), observed),
                new EpisodeChange(EpisodeChange.Kind.UPDATED, observed));
    }

    /** A bucket above the open z-score after the warm-up: counted, and it opens an episode when it is the last needed. */
    private Step high(String routeId, int directionId, BaselineState baseline, BucketObservation bucket, double z) {
        Instant end = bucket.end();
        int high = baseline.consecutiveHigh() + 1;
        if (high < thresholds.openConsecutive()) {
            // An outlier does not update the baseline, so that the first high bucket cannot pull it up and make the
            // second one look normal.
            return quiet(counters(baseline, end, high, baseline.consecutiveLow(), null));
        }
        Instant start = end.minus(thresholds.bucket().multipliedBy(thresholds.openConsecutive()));
        double sigmaEff = Math.max(baseline.sigma(), sigmaFloor);
        DisruptionEpisode opened = DisruptionEpisode.opened(
                InsightIds.disruption(routeId, directionId, start),
                routeId,
                directionId,
                start,
                baseline,
                bucket,
                z,
                stopsAbove(bucket, baseline.mean() + thresholds.openZ() * sigmaEff));
        BaselineState next = counters(baseline, end, 0, 0, opened.id());
        return new Step(new DirectionState(next, opened), new EpisodeChange(EpisodeChange.Kind.OPENED, opened));
    }

    /** The EWMA update: {@code μ += α·diff}, {@code var = (1 − α)(var + α·diff²)}. */
    private BaselineState updated(BaselineState baseline, double x, Instant end) {
        double alpha = thresholds.alpha();
        double diff = x - baseline.mean();
        return new BaselineState(
                baseline.mean() + alpha * diff,
                (1 - alpha) * (baseline.variance() + alpha * diff * diff),
                baseline.bucketCount() + 1,
                end,
                0,
                baseline.consecutiveLow(),
                baseline.openEpisodeId());
    }

    /** {@code baseline_mean + open-z × σ_eff} of an open episode: the delay from which a stop counts as affected. */
    private double threshold(DisruptionEpisode episode) {
        return episode.baselineMean() + thresholds.openZ() * Math.max(episode.baselineStddev(), sigmaFloor);
    }

    private static List<String> stopsAbove(BucketObservation bucket, double threshold) {
        return bucket.maxDelayByStop().entrySet().stream()
                .filter(entry -> entry.getValue() >= threshold)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static BaselineState counters(BaselineState baseline, Instant end, int high, int low, @Nullable UUID open) {
        return new BaselineState(baseline.mean(), baseline.variance(), baseline.bucketCount(), end, high, low, open);
    }

    /** A step that changes no episode. */
    private static Step quiet(BaselineState next) {
        return new Step(new DirectionState(next, null), null);
    }

    private static Step closed(BaselineState next, DisruptionEpisode episode) {
        return new Step(new DirectionState(next, null), new EpisodeChange(EpisodeChange.Kind.CLOSED, episode));
    }
}
