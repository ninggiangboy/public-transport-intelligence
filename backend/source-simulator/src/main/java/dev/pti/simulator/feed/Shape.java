package dev.pti.simulator.feed;

import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * A polyline with the cumulative distance of every point in metres (DOC-25 §5.5). Built from
 * {@code shape_dist_traveled} when the feed has it, from haversine distances otherwise.
 */
public final class Shape {

    private final String id;
    private final double[] lat;
    private final double[] lon;
    private final double[] dist;

    /** @param dist cumulative distances, or {@code null} to compute them from the points */
    public Shape(String id, double[] lat, double[] lon, double @Nullable [] dist) {
        if (lat.length == 0 || lat.length != lon.length) {
            throw new IllegalArgumentException("Shape " + id + " needs at least one point");
        }
        this.id = id;
        this.lat = lat.clone();
        this.lon = lon.clone();
        this.dist = dist != null && !hasGaps(dist) ? monotone(dist) : computeDistances(lat, lon);
    }

    /** A straight polyline through the stops of a trip without a shape. */
    public static Shape through(String id, Stop[] stops) {
        double[] lat = new double[stops.length];
        double[] lon = new double[stops.length];
        for (int i = 0; i < stops.length; i++) {
            lat[i] = stops[i].lat();
            lon[i] = stops[i].lon();
        }
        return new Shape(id, lat, lon, null);
    }

    public String id() {
        return id;
    }

    public double length() {
        return dist[dist.length - 1];
    }

    /** The point at distance {@code d} along the shape, clamped to its ends. */
    public Point pointAt(double d) {
        if (lat.length == 1) {
            return new Point(lat[0], lon[0], 0);
        }
        int i = segmentAt(d);
        double span = dist[i + 1] - dist[i];
        double f = span <= 0 ? 0 : Math.clamp((d - dist[i]) / span, 0, 1);
        double la = lat[i] + f * (lat[i + 1] - lat[i]);
        double lo = lon[i] + f * (lon[i + 1] - lon[i]);
        return new Point(la, lo, bearingOf(i));
    }

    /**
     * The distance along the shape of the point closest to ({@code la}, {@code lo}), searching forward from
     * {@code from} so that the stops of a trip that loops back over itself stay in order.
     */
    public double project(double la, double lo, double from) {
        double best = from;
        double bestDistance = Double.MAX_VALUE;
        double cosLat = Math.cos(Math.toRadians(la));
        for (int i = Math.max(0, segmentAt(from)); i < lat.length - 1; i++) {
            double ax = (lon[i] - lo) * cosLat;
            double ay = lat[i] - la;
            double bx = (lon[i + 1] - lo) * cosLat;
            double by = lat[i + 1] - la;
            double dx = bx - ax;
            double dy = by - ay;
            double len2 = dx * dx + dy * dy;
            double t = len2 == 0 ? 0 : Math.clamp(-(ax * dx + ay * dy) / len2, 0, 1);
            double px = ax + t * dx;
            double py = ay + t * dy;
            double squared = px * px + py * py;
            double along = dist[i] + t * (dist[i + 1] - dist[i]);
            if (squared < bestDistance && along >= from) {
                bestDistance = squared;
                best = along;
            }
        }
        return best;
    }

    private int segmentAt(double d) {
        int i = Arrays.binarySearch(dist, d);
        if (i < 0) {
            i = -i - 2;
        }
        // Several points can share a distance; take the last segment that starts at or before d.
        while (i + 1 < dist.length - 1 && dist[i + 1] <= d) {
            i++;
        }
        return Math.clamp(i, 0, dist.length - 2);
    }

    /** The bearing of the first segment at or after {@code i} that has a length. */
    private double bearingOf(int i) {
        for (int j = i; j < lat.length - 1; j++) {
            if (lat[j] != lat[j + 1] || lon[j] != lon[j + 1]) {
                return Geo.bearing(lat[j], lon[j], lat[j + 1], lon[j + 1]);
            }
        }
        for (int j = i; j > 0; j--) {
            if (lat[j - 1] != lat[j] || lon[j - 1] != lon[j]) {
                return Geo.bearing(lat[j - 1], lon[j - 1], lat[j], lon[j]);
            }
        }
        return 0;
    }

    private static boolean hasGaps(double[] dist) {
        for (double d : dist) {
            if (Double.isNaN(d)) {
                return true;
            }
        }
        return false;
    }

    private static double[] monotone(double[] dist) {
        double[] out = dist.clone();
        for (int i = 1; i < out.length; i++) {
            out[i] = Math.max(out[i], out[i - 1]);
        }
        return out;
    }

    private static double[] computeDistances(double[] lat, double[] lon) {
        double[] out = new double[lat.length];
        for (int i = 1; i < lat.length; i++) {
            out[i] = out[i - 1] + Geo.distance(lat[i - 1], lon[i - 1], lat[i], lon[i]);
        }
        return out;
    }

    /** A position on the shape and the direction of travel there. */
    public record Point(double lat, double lon, double bearing) {}
}
