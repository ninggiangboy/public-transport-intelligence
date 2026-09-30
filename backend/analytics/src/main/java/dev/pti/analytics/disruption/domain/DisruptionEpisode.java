package dev.pti.analytics.disruption.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A disruption episode as {@code insight_service_disruption} holds it (DOC-23 §6.3, §6.5). Every number is kept at
 * the precision of its column ({@code NUMERIC(8,1)}, {@code NUMERIC(6,2)}, half-up), so that an episode read back from
 * the database behaves in the next bucket exactly like the one that was held in memory, and a run split into several
 * {@code advance} calls gives the same rows as one run.
 *
 * @param start start of the first qualifying bucket; with the route and direction it makes the id (DOC-23 §2.3)
 * @param end the end of the bucket that closed the episode; {@code null} while it is open
 * @param closeReason {@code null} while the episode is open
 * @param baselineMean the baseline mean when the episode opened; the baseline is frozen while it is open
 * @param baselineStddev the baseline standard deviation when the episode opened
 * @param affectedStopIds stops that had an arrival above the threshold, sorted, at most
 *     {@value DisruptionStateMachine#MAX_AFFECTED_STOPS}
 * @param lastBucket the end of the last bucket with enough samples that the episode saw
 */
public record DisruptionEpisode(
        UUID id,
        String routeId,
        int directionId,
        Instant start,
        @Nullable Instant end,
        @Nullable CloseReason closeReason,
        double baselineMean,
        double baselineStddev,
        double currentAvg,
        double currentZ,
        double peakAvg,
        double peakZ,
        int sampleCount,
        List<String> affectedStopIds,
        Instant lastBucket) {

    /** The limit of {@code NUMERIC(6,2)} (DOC-23 §15): a z-score beyond it is cut. */
    private static final double MAX_Z = 9999.99;

    public DisruptionEpisode {
        if ((end == null) != (closeReason == null)) {
            throw new IllegalArgumentException(
                    "An episode has an end and a close reason when it is closed, else neither");
        }
        affectedStopIds = List.copyOf(affectedStopIds);
    }

    /** An episode that opens now, with the values of the bucket that made it qualify. */
    static DisruptionEpisode opened(
            UUID id,
            String routeId,
            int directionId,
            Instant start,
            BaselineState baseline,
            BucketObservation bucket,
            double z,
            Collection<String> affectedStops) {
        double avg = round1(bucket.meanDelay());
        double zScore = roundZ(z);
        return new DisruptionEpisode(
                id,
                routeId,
                directionId,
                start,
                null,
                null,
                round1(baseline.mean()),
                round1(baseline.sigma()),
                avg,
                zScore,
                avg,
                zScore,
                bucket.sampleCount(),
                capped(affectedStops),
                bucket.end());
    }

    public boolean isOpen() {
        return closeReason == null;
    }

    /** The episode after a bucket with enough samples: new current values, a higher peak, more stops. */
    DisruptionEpisode observed(BucketObservation bucket, double z, Collection<String> newAffectedStops) {
        double avg = round1(bucket.meanDelay());
        double zScore = roundZ(z);
        boolean newPeak = zScore > peakZ;
        TreeSet<String> stops = new TreeSet<>(affectedStopIds);
        stops.addAll(newAffectedStops);
        return new DisruptionEpisode(
                id,
                routeId,
                directionId,
                start,
                end,
                closeReason,
                baselineMean,
                baselineStddev,
                avg,
                zScore,
                newPeak ? avg : peakAvg,
                newPeak ? zScore : peakZ,
                bucket.sampleCount(),
                capped(stops),
                bucket.end());
    }

    DisruptionEpisode closed(Instant at, CloseReason reason) {
        return new DisruptionEpisode(
                id,
                routeId,
                directionId,
                start,
                at,
                reason,
                baselineMean,
                baselineStddev,
                currentAvg,
                currentZ,
                peakAvg,
                peakZ,
                sampleCount,
                affectedStopIds,
                lastBucket);
    }

    /** Sorted and cut to the first 100: the first 100 of a union are the first 100 of the first 100 of its parts. */
    private static List<String> capped(Collection<String> stops) {
        return new TreeSet<>(stops)
                .stream().limit(DisruptionStateMachine.MAX_AFFECTED_STOPS).toList();
    }

    /** Half-up to one decimal, the way the {@code NUMERIC(8,1)} columns store it. */
    static double round1(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    /** Half-up to two decimals within {@code NUMERIC(6,2)}. */
    static double roundZ(double z) {
        double clamped = Math.max(-MAX_Z, Math.min(MAX_Z, z));
        return BigDecimal.valueOf(clamped).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
