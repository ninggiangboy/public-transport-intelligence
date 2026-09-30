package dev.pti.analytics.core.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The event-time grid, watermark and catch-up arithmetic of DOC-23 §2.2 as pure functions. A detector evaluates only
 * at grid points {@code k × interval} counted from the epoch, so its result does not depend on how the data was split
 * into micro-batches. Every function here takes "now" as an argument; none reads a clock.
 */
public final class EventTimeGrid {

    private EventTimeGrid() {}

    /** The largest grid point that is not after {@code t}. */
    public static Instant floorGrid(Instant t, Duration interval) {
        long step = stepSeconds(interval);
        return Instant.ofEpochSecond(Math.floorDiv(t.getEpochSecond(), step) * step);
    }

    /** The smallest grid point that is not before {@code t}. */
    public static Instant ceilGrid(Instant t, Duration interval) {
        Instant floor = floorGrid(t, interval);
        return floor.equals(t) ? t : floor.plusSeconds(stepSeconds(interval));
    }

    /**
     * The watermark of a route (DOC-23 §2.2): the latest event time that may be evaluated,
     * {@code min(now, max(maxSourceTs − allowedLateness, now − idleTimeout))}. The idle branch lets a route that
     * stopped receiving data still close its episodes; a route without data ({@code maxSourceTs == null}) has only
     * that branch.
     *
     * @param now business time now
     * @param maxSourceTs the newest event time of the source rows of the route in the last two service days
     */
    public static Instant watermark(
            Instant now, @Nullable Instant maxSourceTs, Duration allowedLateness, Duration idleTimeout) {
        Instant idle = now.minus(idleTimeout);
        Instant byData = maxSourceTs == null ? idle : maxSourceTs.minus(allowedLateness);
        Instant candidate = byData.isAfter(idle) ? byData : idle;
        return candidate.isAfter(now) ? now : candidate;
    }

    /** The cursor of a route that has none: one step before the grid point of the first micro-batch (DOC-23 §5.6). */
    public static Instant initialCursor(@Nullable Instant batchMinEventTs, Instant lastGridPoint, Duration interval) {
        Instant anchor = batchMinEventTs == null ? lastGridPoint : batchMinEventTs;
        return floorGrid(anchor, interval).minus(interval);
    }

    /** The grid points {@code g} with {@code cursor < g ≤ upTo}, oldest first. */
    public static List<Instant> pointsAfter(Instant cursor, Instant upTo, Duration interval) {
        long step = stepSeconds(interval);
        List<Instant> points = new ArrayList<>();
        for (Instant g = floorGrid(cursor, interval).plusSeconds(step); !g.isAfter(upTo); g = g.plusSeconds(step)) {
            points.add(g);
        }
        return points;
    }

    /**
     * The catch-up limit (DOC-23 §2.2): when the route is more than {@code maxCatchUp} behind, the cursor jumps to
     * {@code floorGrid(watermark − maxCatchUp)} and the grid points in between are skipped.
     *
     * @param cursor the last evaluated grid point
     * @param watermark the watermark of the route
     * @return the cursor to continue from and the number of grid points skipped, zero when within the limit
     */
    public static CatchUp limitCatchUp(Instant cursor, Instant watermark, Duration maxCatchUp, Duration interval) {
        Instant last = floorGrid(watermark, interval);
        if (Duration.between(cursor, last).compareTo(maxCatchUp) <= 0) {
            return new CatchUp(cursor, 0);
        }
        Instant target = floorGrid(watermark.minus(maxCatchUp), interval);
        if (!target.isAfter(cursor)) {
            return new CatchUp(cursor, 0);
        }
        return new CatchUp(target, Duration.between(cursor, target).getSeconds() / stepSeconds(interval));
    }

    /** The cursor after {@link #limitCatchUp} and how many grid points it skipped. */
    public record CatchUp(Instant cursor, long skippedPoints) {}

    private static long stepSeconds(Duration interval) {
        if (interval.isNegative() || interval.isZero() || interval.toNanosPart() != 0) {
            throw new IllegalArgumentException("A grid interval is a positive whole number of seconds: " + interval);
        }
        return interval.getSeconds();
    }
}
