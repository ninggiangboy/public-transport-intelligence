package dev.pti.analytics.bunching.domain;

import dev.pti.analytics.config.AnalyticsPropertiesFixtures;
import dev.pti.analytics.reference.domain.PatternStop;
import dev.pti.analytics.reference.domain.StopTime;
import dev.pti.analytics.reference.domain.TripPattern;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The fixture of DOC-23 §18.1: a trip of ten stops {@code S0 … S9} with {@code stop_sequence} 1…10, {@code dist} 0,
 * 500, … 4,500 and, at {@code S_k}, arrival and departure at 12:00 + k minutes. The stops lie on a meridian, so the
 * position at a given progress can be placed exactly.
 */
public final class BunchingFixtures {

    public static final LocalDate DAY = LocalDate.parse("2026-09-29");
    public static final String TRIP = "T1";
    public static final double LAT0 = 44.9;
    public static final double LON = -93.27;
    /** Meters per degree of latitude for the radius {@code Geo} uses, so that 500 m is exactly 500 m. */
    private static final double METERS_PER_DEGREE = 6_371_008.8 * Math.PI / 180;

    private BunchingFixtures() {}

    public static Instant at(String time) {
        return Instant.parse(DAY + "T" + time + "Z");
    }

    public static BunchingThresholds thresholds() {
        return AnalyticsPropertiesFixtures.defaults().bunching().toThresholds();
    }

    public static double lat(double dist) {
        return LAT0 + dist / METERS_PER_DEGREE;
    }

    public static TripPattern trip() {
        return trip(TRIP, 0, 10);
    }

    /** Stops {@code S<first> …} of the fixture, renumbered from sequence 1 with their own progress from 0. */
    public static TripPattern trip(String tripId, int firstStop, int count) {
        List<PatternStop> stops = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int k = firstStop + i;
            int seconds = 12 * 3600 + 60 * k;
            stops.add(new PatternStop(i + 1, "S" + k, seconds, seconds, 500.0 * i, lat(500.0 * i), LON));
        }
        return new TripPattern(tripId, "R", 0, stops);
    }

    /** The same trip without {@code shape_dist_traveled}: progress is the cumulative haversine distance. */
    public static TripPattern tripWithoutShapeDistance() {
        List<StopTime> stopTimes = new ArrayList<>();
        for (int k = 0; k < 10; k++) {
            int seconds = 12 * 3600 + 60 * k;
            stopTimes.add(new StopTime(k + 1, "S" + k, seconds, seconds, null, lat(500.0 * k), LON));
        }
        return TripPattern.fromStopTimes(TRIP, "R", 0, stopTimes);
    }

    /**
     * A position {@code progress} meters along the trip, with the next stop {@code nextStop} (an index, 0-based).
     */
    public static VehiclePosition position(
            String vehicle,
            String time,
            int nextStop,
            StopStatus status,
            double progress,
            String tripId,
            int direction) {
        return new VehiclePosition(vehicle, at(time), DAY, tripId, direction, lat(progress), LON, nextStop + 1, status);
    }

    public static VehiclePosition position(
            String vehicle, String time, int nextStop, StopStatus status, double progress) {
        return position(vehicle, time, nextStop, status, progress, TRIP, 0);
    }

    /** A vehicle standing at stop {@code stop}. */
    public static VehiclePosition stopped(String vehicle, String time, int stop) {
        return position(vehicle, time, stop, StopStatus.STOPPED_AT, 500.0 * stop);
    }

    /** A vehicle on its way to stop {@code nextStop}, halfway from the previous stop. */
    public static VehiclePosition heading(String vehicle, String time, int nextStop) {
        return position(vehicle, time, nextStop, StopStatus.IN_TRANSIT_TO, 500.0 * nextStop - 250);
    }

    /** A schedule backed by a map of trips and one headway for every query, or none. */
    static final class FakeSchedule implements BunchingSchedule {

        private final Map<String, TripPattern> trips = new HashMap<>();
        private final OptionalInt headway;

        FakeSchedule(OptionalInt headway, TripPattern... patterns) {
            this.headway = headway;
            for (TripPattern pattern : patterns) {
                trips.put(pattern.tripId(), pattern);
            }
        }

        static FakeSchedule withHeadway(int seconds, TripPattern... patterns) {
            return new FakeSchedule(OptionalInt.of(seconds), patterns);
        }

        @Override
        public Optional<TripPattern> trip(String tripId) {
            return Optional.ofNullable(trips.get(tripId));
        }

        @Override
        public OptionalInt scheduledHeadway(String routeId, int directionId, LocalDate serviceDate, Instant at) {
            return headway;
        }
    }
}
