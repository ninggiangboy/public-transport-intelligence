package dev.pti.analytics.bunching.domain;

import dev.pti.analytics.core.domain.Geo;
import dev.pti.analytics.reference.domain.PatternStop;
import dev.pti.analytics.reference.domain.TripPattern;
import java.util.List;

/**
 * Where a vehicle is along its trip and when the schedule puts it there (DOC-23 §5.2). Progress has the unit of
 * {@link PatternStop#dist()}; it is only ever compared and interpolated, so the unit does not matter.
 */
public final class TripGeometry {

    private TripGeometry() {}

    /**
     * The progress {@code d(v)} of a position: the distance of its stop when it stands there or the stop is the
     * first one, otherwise the distance of the previous stop plus the share of the way the position has covered,
     * measured by the great-circle distances to the two stops.
     *
     * @param stopIndex the index in the pattern of the stop the vehicle is at or heading to
     */
    public static double progress(TripPattern pattern, int stopIndex, VehiclePosition position) {
        List<PatternStop> stops = pattern.stops();
        PatternStop next = stops.get(stopIndex);
        if (position.status() == StopStatus.STOPPED_AT || stopIndex == 0) {
            return next.dist();
        }
        PatternStop previous = stops.get(stopIndex - 1);
        double fromPrevious = Geo.haversineMeters(previous.lat(), previous.lon(), position.lat(), position.lon());
        double toNext = Geo.haversineMeters(position.lat(), position.lon(), next.lat(), next.lon());
        double total = fromPrevious + toNext;
        double fraction = total == 0 ? 1 : fromPrevious / total;
        return previous.dist() + fraction * (next.dist() - previous.dist());
    }

    /**
     * The time the schedule puts a vehicle at progress {@code x}, in GTFS seconds: a linear interpolation between the
     * departure from one stop and the arrival at the next. {@code x} is clamped to the trip.
     */
    public static double scheduledSeconds(TripPattern pattern, double x) {
        List<PatternStop> stops = pattern.stops();
        int last = stops.size() - 1;
        if (last == 0) {
            return stops.getFirst().arrivalSeconds();
        }
        double clamped =
                Math.max(stops.getFirst().dist(), Math.min(stops.get(last).dist(), x));
        int i = 0;
        while (i < last - 1 && clamped > stops.get(i + 1).dist()) {
            i++;
        }
        PatternStop from = stops.get(i);
        PatternStop to = stops.get(i + 1);
        double span = to.dist() - from.dist();
        if (span == 0) {
            return to.arrivalSeconds();
        }
        return from.departureSeconds()
                + (clamped - from.dist()) / span * (to.arrivalSeconds() - from.departureSeconds());
    }
}
