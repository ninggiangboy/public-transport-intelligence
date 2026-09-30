package dev.pti.analytics.disruption.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The arrivals of one route, read once, from which the sliding window of each bucket is cut in memory (DOC-23 §6.1).
 * The live detector and the recompute (§11.4) build their observations with this class, so a bucket gets the same
 * value whichever of them looks at it. A window is {@code [end − window, end)}: an arrival exactly at the end belongs
 * to the next bucket.
 */
public final class ArrivalSeries {

    private final Duration window;
    private final int minSamples;
    private final Map<Integer, List<Arrival>> byDirection = new HashMap<>();

    public ArrivalSeries(List<Arrival> arrivals, DisruptionThresholds thresholds) {
        this.window = thresholds.window();
        this.minSamples = thresholds.minSamples();
        for (Arrival arrival : arrivals) {
            byDirection
                    .computeIfAbsent(arrival.directionId(), d -> new ArrayList<>())
                    .add(arrival);
        }
        byDirection.values().forEach(list -> list.sort(Comparator.comparing(Arrival::observedAt)));
    }

    /** The observation of the bucket that ends at {@code end}, for one direction. */
    public BucketObservation observe(int directionId, Instant end) {
        List<Arrival> sorted = byDirection.get(directionId);
        if (sorted == null) {
            return BucketObservation.empty(end);
        }
        int lo = firstAtOrAfter(sorted, end.minus(window));
        int hi = firstAtOrAfter(sorted, end);
        int count = hi - lo;
        if (count == 0) {
            return BucketObservation.empty(end);
        }
        long sum = 0;
        for (int i = lo; i < hi; i++) {
            sum += sorted.get(i).delaySeconds();
        }
        Map<String, Integer> maxByStop = new HashMap<>();
        if (count >= minSamples) {
            for (int i = lo; i < hi; i++) {
                Arrival arrival = sorted.get(i);
                maxByStop.merge(arrival.stopId(), arrival.delaySeconds(), Math::max);
            }
        }
        return new BucketObservation(end, count, (double) sum / count, maxByStop);
    }

    /** Index of the first arrival that is not before {@code t}; {@code size()} when there is none. */
    private static int firstAtOrAfter(List<Arrival> sorted, Instant t) {
        int lo = 0;
        int hi = sorted.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted.get(mid).observedAt().isBefore(t)) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }
}
