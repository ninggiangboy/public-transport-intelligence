package dev.pti.analytics.disruption.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * What the arrivals of one direction say about one bucket (DOC-23 §6.1): how many there are in the sliding window that
 * ends with the bucket, their mean delay, and the largest delay seen at each stop.
 *
 * @param end the end of the bucket, a whole minute
 * @param sampleCount arrivals in {@code [end − window, end)}
 * @param meanDelay their mean delay in seconds; 0 when there are none
 * @param maxDelayByStop the largest delay at each stop of the window; empty when the window has fewer samples than
 *     {@code min-samples}, because such a bucket never reads it
 */
public record BucketObservation(Instant end, int sampleCount, double meanDelay, Map<String, Integer> maxDelayByStop) {

    public BucketObservation {
        maxDelayByStop = Collections.unmodifiableMap(maxDelayByStop);
    }

    /** A bucket without arrivals. */
    public static BucketObservation empty(Instant end) {
        return new BucketObservation(end, 0, 0, Map.of());
    }
}
