package dev.pti.simulator.motion;

import java.time.LocalDate;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The AR(1) delay factor shared by the vehicles of one route and direction (DOC-25 §5.4):
 * {@code c_0 = 0, c_k = phi * c_(k-1) + N(0, sigma)} per 5-minute bucket, seeded by
 * {@code (seed, service date, route, direction, k)}.
 */
public final class RouteFactors {

    /** 34 hours of buckets covers GTFS times up to 26:34 with room to spare. */
    private static final int HORIZON_SECONDS = 34 * 3600;

    private final long seed;
    private final double phi;
    private final double sigma;
    private final long bucketSeconds;
    private final Map<Key, double[]> series = new ConcurrentHashMap<>();

    public RouteFactors(long seed, DelayParameters parameters) {
        this.seed = seed;
        this.phi = parameters.routeFactorPhi();
        this.sigma = parameters.routeFactorSigma();
        this.bucketSeconds = parameters.routeFactorBucket().toSeconds();
    }

    /** The factor at {@code secondsOfDay} (GTFS seconds of the service date). */
    public double at(LocalDate serviceDate, String routeId, int directionId, long secondsOfDay) {
        double[] c = series.computeIfAbsent(new Key(serviceDate, routeId, directionId), this::build);
        int k = Math.clamp(Math.floorDiv(secondsOfDay, bucketSeconds), 0, c.length - 1);
        return c[k];
    }

    /** Drops the series of service dates before {@code date}. */
    public void evictBefore(LocalDate date) {
        series.keySet().removeIf(key -> key.serviceDate().isBefore(date));
    }

    private double[] build(Key key) {
        double[] c = new double[(int) (HORIZON_SECONDS / bucketSeconds) + 1];
        for (int k = 1; k < c.length; k++) {
            SplittableRandom rng = new SplittableRandom(
                    Seeds.of(seed, "route-factor", key.serviceDate(), key.routeId(), key.directionId(), k));
            c[k] = phi * c[k - 1] + rng.nextGaussian(0, sigma);
        }
        return c;
    }

    private record Key(LocalDate serviceDate, String routeId, int directionId) {}
}
