package dev.pti.analytics.reference.domain;

import dev.pti.analytics.core.domain.Geo;
import java.util.ArrayList;
import java.util.List;

/**
 * The stop pattern of a trip (DOC-23 §3). It has no date: the day comes with the fact row that refers to the trip.
 *
 * @param stops in increasing {@code stopSequence}
 */
public record TripPattern(String tripId, String routeId, int directionId, List<PatternStop> stops) {

    public TripPattern {
        stops = List.copyOf(stops);
        for (int i = 1; i < stops.size(); i++) {
            if (stops.get(i).stopSequence() <= stops.get(i - 1).stopSequence()) {
                throw new IllegalArgumentException("Stops of trip " + tripId + " are not in increasing sequence");
            }
        }
    }

    /**
     * Builds the pattern of a trip from its stop times. {@code dist} is {@code shape_dist_traveled} when every stop
     * has a value; otherwise the cumulative haversine distance in meters between consecutive stops (DOC-23 §3 notes).
     *
     * @param stopTimes in increasing {@code stop_sequence}
     */
    public static TripPattern fromStopTimes(String tripId, String routeId, int directionId, List<StopTime> stopTimes) {
        boolean allHaveShapeDist = stopTimes.stream().allMatch(s -> s.shapeDistTraveled() != null);
        List<PatternStop> stops = new ArrayList<>(stopTimes.size());
        double cumulative = 0;
        StopTime previous = null;
        for (StopTime stop : stopTimes) {
            if (previous != null) {
                cumulative += Geo.haversineMeters(previous.lat(), previous.lon(), stop.lat(), stop.lon());
            }
            Double shapeDist = stop.shapeDistTraveled();
            double dist = allHaveShapeDist && shapeDist != null ? shapeDist : cumulative;
            stops.add(new PatternStop(
                    stop.stopSequence(),
                    stop.stopId(),
                    stop.arrivalSeconds(),
                    stop.departureSeconds(),
                    dist,
                    stop.lat(),
                    stop.lon()));
            previous = stop;
        }
        return new TripPattern(tripId, routeId, directionId, stops);
    }

    /** The index in {@link #stops()} of a {@code stop_sequence}, or {@code -1} when the trip has no such stop. */
    public int indexOfSequence(int stopSequence) {
        int low = 0;
        int high = stops.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int candidate = stops.get(middle).stopSequence();
            if (candidate < stopSequence) {
                low = middle + 1;
            } else if (candidate > stopSequence) {
                high = middle - 1;
            } else {
                return middle;
            }
        }
        return -1;
    }
}
