package dev.pti.api.transit.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Douglas-Peucker simplification of a route shape (DOC-32 E-02): coordinates are rounded to six decimals, then points
 * closer than the tolerance to the line between their neighbours are dropped. The distance is measured in degrees, as
 * the tolerance is (0.00002 degrees, about two metres).
 */
public final class LineSimplifier {

    /** About two metres (DOC-32 E-02). */
    public static final double TOLERANCE_DEGREES = 0.00002;

    private static final double ROUNDING = 1_000_000.0;

    private LineSimplifier() {}

    public static List<GeoPoint> simplify(List<GeoPoint> points, double tolerance) {
        List<GeoPoint> rounded = points.stream()
                .map(point -> new GeoPoint(round(point.lon()), round(point.lat())))
                .toList();
        if (rounded.size() <= 2) {
            return rounded;
        }
        boolean[] keep = new boolean[rounded.size()];
        keep[0] = true;
        keep[rounded.size() - 1] = true;
        markFarthest(rounded, tolerance, keep);
        List<GeoPoint> result = new ArrayList<>();
        for (int i = 0; i < rounded.size(); i++) {
            if (keep[i]) {
                result.add(rounded.get(i));
            }
        }
        return result;
    }

    // Iterative, so that a shape of several thousand points cannot overflow the stack.
    private static void markFarthest(List<GeoPoint> points, double tolerance, boolean[] keep) {
        List<int[]> pending = new ArrayList<>();
        pending.add(new int[] {0, points.size() - 1});
        while (!pending.isEmpty()) {
            int[] range = pending.remove(pending.size() - 1);
            int start = range[0];
            int end = range[1];
            double farthest = -1;
            int index = -1;
            for (int i = start + 1; i < end; i++) {
                double distance = distance(points.get(i), points.get(start), points.get(end));
                if (distance > farthest) {
                    farthest = distance;
                    index = i;
                }
            }
            if (index >= 0 && farthest > tolerance) {
                keep[index] = true;
                pending.add(new int[] {start, index});
                pending.add(new int[] {index, end});
            }
        }
    }

    private static double distance(GeoPoint point, GeoPoint from, GeoPoint to) {
        double dx = to.lon() - from.lon();
        double dy = to.lat() - from.lat();
        double lengthSquared = dx * dx + dy * dy;
        if (lengthSquared == 0) {
            return Math.hypot(point.lon() - from.lon(), point.lat() - from.lat());
        }
        double t = ((point.lon() - from.lon()) * dx + (point.lat() - from.lat()) * dy) / lengthSquared;
        double clamped = Math.max(0, Math.min(1, t));
        return Math.hypot(point.lon() - (from.lon() + clamped * dx), point.lat() - (from.lat() + clamped * dy));
    }

    private static double round(double value) {
        return Math.round(value * ROUNDING) / ROUNDING;
    }
}
