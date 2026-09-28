package dev.pti.simulator.motion;

import dev.pti.simulator.feed.TripSchedule;
import java.time.Instant;
import java.time.ZoneId;
import java.util.SplittableRandom;

/**
 * Samples the delay of each segment of a trip (DOC-25 §5.3). Every draw is seeded from
 * {@code (seed, service date, trip, stop)}, so a trip replays identically for the same seed.
 */
public final class DelayModel {

    /** Travel time used when two consecutive stops share a scheduled time. */
    private static final double MIN_ZERO_TRAVEL_SECONDS = 15;

    private final long seed;
    private final DelayParameters parameters;
    private final RouteFactors routeFactors;
    private final ZoneId zone;

    public DelayModel(long seed, DelayParameters parameters, ZoneId zone) {
        this.seed = seed;
        this.parameters = parameters;
        this.routeFactors = new RouteFactors(seed, parameters);
        this.zone = zone;
    }

    public long seed() {
        return seed;
    }

    public RouteFactors routeFactors() {
        return routeFactors;
    }

    /** The departure delay at the first stop, in seconds: never early (DOC-25 §5.3). */
    double initialDelay(TripRun run) {
        Period period = period(run, run.schedule().departure(0));
        SplittableRandom rng = new SplittableRandom(
                Seeds.of(seed, "initial", run.serviceDate(), run.schedule().tripId()));
        double d = rng.nextGaussian(
                parameters.initialMean().of(period), parameters.initialSd().of(period));
        return Math.clamp(d, 0, parameters.lateLimit());
    }

    /**
     * Samples segment {@code i -> i+1} for a vehicle leaving stop {@code i} at {@code departureMillis} with
     * {@code departureDelay} seconds of delay.
     */
    Segment sample(TripRun run, int i, long departureMillis, double departureDelay) {
        TripSchedule s = run.schedule();
        Period period = period(run, s.departure(i));
        SplittableRandom rng =
                new SplittableRandom(Seeds.of(seed, "segment", run.serviceDate(), s.tripId(), s.stopSequence(i)));
        long secondsOfDay = Math.floorDiv(departureMillis - run.baseMillis(), 1000);
        double c = routeFactors.at(run.serviceDate(), s.routeId(), s.directionId(), secondsOfDay);
        double eps = rng.nextGaussian(
                parameters.drift().of(period) * (1 + c), parameters.segmentSd().of(period));
        double arrivalDelay = Math.clamp(departureDelay + eps, parameters.earlyLimit(), parameters.lateLimit());

        double travelSched = s.arrival(i + 1) - s.departure(i);
        double travel = travelSched == 0
                ? Math.max(eps, MIN_ZERO_TRAVEL_SECONDS)
                : Math.max(
                        travelSched + (arrivalDelay - departureDelay),
                        Math.max(parameters.minSpeedRatio() * travelSched, 1));
        long arrivalMillis = departureMillis + Math.round(travel * 1000);

        boolean last = i + 1 == s.lastIndex();
        long nextDepartureMillis = arrivalMillis;
        if (!last) {
            double dwellExtra = s.servesPassengers(i + 1)
                    ? rng.nextExponential() * parameters.dwellMean().of(period)
                    : 0;
            double nextDelay = arrivalDelay + dwellExtra;
            if (s.timepoint(i + 1) && nextDelay < 0) {
                nextDelay = 0;
            }
            long scheduled = run.scheduledMillis(s.departure(i + 1));
            nextDepartureMillis = Math.max(scheduled + Math.round(nextDelay * 1000), arrivalMillis);
        }
        return new Segment(arrivalMillis, nextDepartureMillis);
    }

    /** Peak or off-peak at a scheduled time of the run (DOC-25 §5.3). */
    Period period(TripRun run, int scheduledSeconds) {
        return Period.at(
                Instant.ofEpochMilli(run.scheduledMillis(scheduledSeconds)).atZone(zone));
    }

    /** Actual arrival at stop {@code i+1} and actual departure from it, in epoch milliseconds. */
    record Segment(long arrivalMillis, long nextDepartureMillis) {}
}
