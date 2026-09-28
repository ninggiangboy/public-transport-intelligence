package dev.pti.simulator.motion;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.common.message.StopTimeUpdate;
import dev.pti.common.message.VehicleStopStatus;
import dev.pti.simulator.feed.Feed;
import dev.pti.simulator.feed.Feeds;
import dev.pti.simulator.feed.Geo;
import dev.pti.simulator.feed.Shape;
import dev.pti.simulator.feed.TripSchedule;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** DOC-25 T-05, T-06 and T-08 on every trip of the mini feed. */
class TripRunTest {

    private static final Feed FEED = Feeds.mini();
    private static final DelayModel MODEL = Runs.model(FEED, 42);
    private static final int LOOKAHEAD = 10;

    private static Collection<TripSchedule> trips() {
        return FEED.trips().values();
    }

    /** T-05: the vehicle only moves forward, stops at every stop and keeps to the model's limits. */
    @Test
    void motionKeepsItsInvariants() {
        for (TripSchedule trip : trips()) {
            TripRun reference = Runs.complete(Runs.run(FEED, MODEL, trip, Runs.TUESDAY));
            assertThat(reference.departureMillis())
                    .isGreaterThanOrEqualTo(reference.scheduledMillis(trip.departure(0)));

            // Motion is only defined at the run's current time, so a live run walks forward through every event.
            TripRun run = Runs.run(FEED, MODEL, trip, Runs.TUESDAY);
            double lastDist = -1;
            for (long t : timeline(reference)) {
                run.advanceTo(t);
                TripRun.Motion m = run.motion(t);
                assertThat(m.dist()).as("%s at %d", trip, t).isGreaterThanOrEqualTo(lastDist);
                lastDist = m.dist();
                int j = arrivedAt(reference, t);
                if (j > 0) {
                    assertThat(m.status()).as("%s at stop %d", trip, j).isEqualTo(VehicleStopStatus.STOPPED_AT);
                    assertThat(m.stopIndex()).isEqualTo(j);
                    Shape.Point p = trip.shape().pointAt(m.dist());
                    Shape.Point stop = trip.shape().pointAt(trip.dist(j));
                    assertThat(Geo.distance(p.lat(), p.lon(), stop.lat(), stop.lon()))
                            .isLessThanOrEqualTo(1.0);
                }
            }

            for (int j = 1; j <= trip.lastIndex(); j++) {
                double scheduled = trip.arrival(j) - trip.departure(j - 1);
                if (scheduled > 0) {
                    assertThat((reference.arrivalMillis(j) - reference.departureMillis(j - 1)) / 1000.0)
                            .as("%s segment %d is at most twice as fast as the schedule", trip, j)
                            .isGreaterThanOrEqualTo(0.5 * scheduled - 0.001);
                }
                if (j < trip.lastIndex() && trip.timepoint(j)) {
                    assertThat(reference.departureMillis(j))
                            .as("%s leaves timepoint %d no earlier than scheduled", trip, j)
                            .isGreaterThanOrEqualTo(reference.scheduledMillis(trip.departure(j)));
                }
            }
        }
    }

    @Test
    void reportsInTransitThenIncomingBetweenStops() {
        TripSchedule trip = longestSegmentTrip();
        TripRun reference = Runs.complete(Runs.run(FEED, MODEL, trip, Runs.TUESDAY));
        int j = longestSegment(trip) + 1;
        long leave = reference.departureMillis(j - 1);
        long arrive = reference.arrivalMillis(j);
        TripRun run = Runs.run(FEED, MODEL, trip, Runs.TUESDAY);

        run.advanceTo(leave + 1_000);
        TripRun.Motion justLeft = run.motion(leave + 1_000);
        run.advanceTo(arrive - 1_000);
        TripRun.Motion almostThere = run.motion(arrive - 1_000);

        assertThat(justLeft.status()).isEqualTo(VehicleStopStatus.IN_TRANSIT_TO);
        assertThat(justLeft.stopIndex()).isEqualTo(j);
        assertThat(justLeft.speed()).isPositive();
        assertThat(almostThere.status()).isEqualTo(VehicleStopStatus.INCOMING_AT);
    }

    /** Every second of the run plus each arrival, the moment before it, and a few instants of each dwell. */
    private static long[] timeline(TripRun reference) {
        TreeSet<Long> times = new TreeSet<>();
        long start = reference.departureMillis() - 60_000;
        for (long t = start; t <= reference.endMillis(); t += 1_000) {
            times.add(t);
        }
        for (int j = 1; j <= reference.schedule().lastIndex(); j++) {
            long arrival = reference.arrivalMillis(j);
            times.add(arrival - 1);
            times.add(arrival);
            if (j < reference.schedule().lastIndex()) {
                long leave = reference.departureMillis(j);
                for (int k = 1; k < 4; k++) {
                    times.add(arrival + (leave - arrival) * k / 4);
                }
            }
        }
        return times.stream().mapToLong(Long::longValue).toArray();
    }

    /** The stop the vehicle stands at in {@code t}: arrived and not left yet; 0 when it is not at a stop. */
    private static int arrivedAt(TripRun reference, long t) {
        TripSchedule trip = reference.schedule();
        for (int j = 1; j <= trip.lastIndex(); j++) {
            long leave = j < trip.lastIndex() ? reference.departureMillis(j) : Long.MAX_VALUE;
            if (reference.arrivalMillis(j) <= t && t < leave) {
                return j;
            }
        }
        return 0;
    }

    /** T-06: the same seed replays the same trip; another seed does not. */
    @Test
    void isDeterministicForASeed() {
        for (TripSchedule trip : trips()) {
            TripRun a = Runs.complete(Runs.run(FEED, Runs.model(FEED, 42), trip, Runs.TUESDAY));
            TripRun b = Runs.complete(Runs.run(FEED, Runs.model(FEED, 42), trip, Runs.TUESDAY));
            TripRun c = Runs.complete(Runs.run(FEED, Runs.model(FEED, 43), trip, Runs.TUESDAY));
            assertThat(arrivals(a)).isEqualTo(arrivals(b));
            assertThat(arrivals(a)).isNotEqualTo(arrivals(c));
        }
    }

    /** T-06 through a restart: a run rebuilt mid-trip is where the uninterrupted one is. */
    @Test
    void aRunRebuiltMidTripMatchesTheUninterruptedOne() {
        TripSchedule trip = longestSegmentTrip();
        TripRun live = Runs.run(FEED, MODEL, trip, Runs.TUESDAY);
        long mid = (live.departureMillis() + live.scheduledMillis(trip.lastArrival())) / 2;
        for (long t = live.departureMillis(); t <= mid; t += 5_000) {
            live.advanceTo(t);
        }
        live.advanceTo(mid);

        TripRun rebuilt = Runs.run(FEED, MODEL, trip, Runs.TUESDAY);
        rebuilt.advanceTo(mid);

        assertThat(rebuilt.motion(mid)).isEqualTo(live.motion(mid));
        assertThat(arrivals(Runs.complete(rebuilt))).isEqualTo(arrivals(Runs.complete(live)));
    }

    /** T-08: the TripUpdate invariants of DOC-25 §6.3, every 30 s over each trip. */
    @Test
    void tripUpdatesKeepTheirInvariants() {
        for (TripSchedule trip : trips()) {
            TripRun run = Runs.run(FEED, MODEL, trip, Runs.TUESDAY);
            long end = Runs.complete(Runs.run(FEED, MODEL, trip, Runs.TUESDAY)).endMillis();
            List<Integer> observed = new ArrayList<>();
            boolean first = true;
            for (long t = run.departureMillis() - 30_000; t <= end + 30_000; t += 30_000) {
                List<StopTimeUpdate> updates = run.tripUpdate(t, LOOKAHEAD);
                checkInvariants(updates, t);
                int observedHere = 0;
                for (StopTimeUpdate u : updates) {
                    if (u.arrival() != null && u.arrival().time().toEpochMilli() <= t) {
                        observed.add(u.stopSequence());
                        observedHere++;
                    }
                }
                if (first) {
                    assertThat(observedHere)
                            .as("the first TripUpdate has no observed part")
                            .isZero();
                    first = false;
                }
            }
            List<Integer> expected = new ArrayList<>();
            for (int j = 1; j <= trip.lastIndex(); j++) {
                expected.add(trip.stopSequence(j));
            }
            assertThat(observed)
                    .as("every stop but the first is observed exactly once")
                    .isEqualTo(expected);
        }
    }

    @Test
    void theFirstTripUpdateAfterARestartHasNoObservedPart() {
        TripSchedule trip = longestSegmentTrip();
        TripRun run = Runs.run(FEED, MODEL, trip, Runs.TUESDAY);
        long mid = (run.departureMillis() + run.scheduledMillis(trip.lastArrival())) / 2;
        run.advanceTo(mid);
        run.markReported(mid);

        List<StopTimeUpdate> updates = run.tripUpdate(mid, LOOKAHEAD);

        checkInvariants(updates, mid);
        assertThat(updates)
                .allSatisfy(u -> assertThat(u.arrival().time().toEpochMilli()).isGreaterThan(mid));
    }

    static void checkInvariants(List<StopTimeUpdate> updates, long t) {
        int previous = -1;
        int predicted = 0;
        for (StopTimeUpdate u : updates) {
            assertThat(u.stopSequence()).isGreaterThan(previous);
            previous = u.stopSequence();
            assertThat(u.arrival() != null || u.departure() != null).isTrue();
            if (u.arrival() != null && u.arrival().time().toEpochMilli() > t) {
                predicted++;
            }
        }
        assertThat(predicted).isLessThanOrEqualTo(LOOKAHEAD);
    }

    private static List<Long> arrivals(TripRun run) {
        List<Long> out = new ArrayList<>();
        for (int j = 1; j <= run.schedule().lastIndex(); j++) {
            out.add(run.arrivalMillis(j));
            out.add(run.departureMillis(j - 1));
        }
        return out;
    }

    private static TripSchedule longestSegmentTrip() {
        return trips().stream()
                .max((a, b) -> Integer.compare(segmentLength(a), segmentLength(b)))
                .orElseThrow();
    }

    private static int segmentLength(TripSchedule trip) {
        int i = longestSegment(trip);
        return trip.arrival(i + 1) - trip.departure(i);
    }

    private static int longestSegment(TripSchedule trip) {
        int best = 0;
        for (int i = 0; i < trip.lastIndex(); i++) {
            if (trip.arrival(i + 1) - trip.departure(i) > trip.arrival(best + 1) - trip.departure(best)) {
                best = i;
            }
        }
        return best;
    }
}
