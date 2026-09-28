package dev.pti.simulator.motion;

import dev.pti.simulator.feed.Geo;
import dev.pti.simulator.feed.Shape;
import java.util.SplittableRandom;

/**
 * A reported vehicle position (DOC-25 §5.5): the point on the shape plus GPS noise seeded by
 * {@code (vehicle_id, event_timestamp)}, speed with ±5% noise, and the bearing rounded to whole degrees.
 *
 * @param speed metres per second
 */
public record Position(double lat, double lon, double bearing, double speed) {

    private static final double SPEED_NOISE = 0.05;

    /** 6 decimals is about 0.1 m, well below the GPS noise. */
    private static final double COORDINATE_SCALE = 1e6;

    public static Position of(
            TripRun run, TripRun.Motion motion, long seed, double gpsNoiseMetres, String vehicleId, long t) {
        Shape.Point p = run.schedule().shape().pointAt(motion.dist());
        SplittableRandom rng = new SplittableRandom(Seeds.of(seed, "gps", vehicleId, t));
        double[] noisy =
                Geo.offset(p.lat(), p.lon(), rng.nextGaussian(0, gpsNoiseMetres), rng.nextGaussian(0, gpsNoiseMetres));
        double speed = motion.speed() * (1 + rng.nextDouble(-SPEED_NOISE, SPEED_NOISE));
        return new Position(
                Math.round(noisy[0] * COORDINATE_SCALE) / COORDINATE_SCALE,
                Math.round(noisy[1] * COORDINATE_SCALE) / COORDINATE_SCALE,
                Math.floorMod(Math.round(p.bearing()), 360),
                Math.round(speed * 100) / 100.0);
    }
}
