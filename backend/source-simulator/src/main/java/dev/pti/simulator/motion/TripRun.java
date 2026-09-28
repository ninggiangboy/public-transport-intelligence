package dev.pti.simulator.motion;

import dev.pti.common.message.ScheduleRelationship;
import dev.pti.common.message.StopTimeEvent;
import dev.pti.common.message.StopTimeUpdate;
import dev.pti.common.message.VehicleStopStatus;
import dev.pti.simulator.feed.TripSchedule;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One run of a trip on a real service date (DOC-25 §5.2). The run moves forward only: segments are sampled when
 * the vehicle leaves a stop, and actual times are kept per stop in epoch milliseconds so that TripUpdates can
 * report what was observed. Not thread-safe; owned by the emitter thread.
 */
public final class TripRun {

    /** Within this time or distance of the next stop, the vehicle is INCOMING_AT (DOC-25 §5.5). */
    private static final long INCOMING_MILLIS = 30_000;

    private static final double INCOMING_METRES = 200;

    private final TripSchedule schedule;
    private final LocalDate serviceDate;
    private final long baseMillis;
    private final DelayModel model;
    private final long[] arrival;
    private final long[] departure;
    private int segment;
    private int lastReportedIndex;

    private TripRun(TripSchedule schedule, LocalDate serviceDate, long baseMillis, DelayModel model) {
        this.schedule = schedule;
        this.serviceDate = serviceDate;
        this.baseMillis = baseMillis;
        this.model = model;
        this.arrival = new long[schedule.stopCount()];
        this.departure = new long[schedule.stopCount()];
        Arrays.fill(arrival, Long.MIN_VALUE);
        Arrays.fill(departure, Long.MIN_VALUE);
    }

    /**
     * Starts a run: the vehicle leaves the first stop after its initial delay, and not before
     * {@code notBeforeMillis} (the arrival of its previous trip).
     *
     * @param baseMillis "noon minus 12h" of the service date, in epoch milliseconds (DOC-13 §3)
     */
    public static TripRun start(
            TripSchedule schedule, LocalDate serviceDate, long baseMillis, DelayModel model, long notBeforeMillis) {
        TripRun run = new TripRun(schedule, serviceDate, baseMillis, model);
        long planned = run.scheduledMillis(schedule.departure(0)) + Math.round(model.initialDelay(run) * 1000);
        run.departure[0] = Math.max(planned, notBeforeMillis);
        run.sample(0);
        return run;
    }

    public TripSchedule schedule() {
        return schedule;
    }

    public LocalDate serviceDate() {
        return serviceDate;
    }

    long baseMillis() {
        return baseMillis;
    }

    long scheduledMillis(int gtfsSeconds) {
        return baseMillis + gtfsSeconds * 1000L;
    }

    /** Moves the run to {@code t}, sampling every segment the vehicle has started by then. */
    public void advanceTo(long t) {
        int last = schedule.lastIndex();
        while (segment + 1 < last && t >= departure[segment + 1]) {
            sample(segment + 1);
        }
    }

    public long departureMillis() {
        return departure[0];
    }

    public boolean departed(long t) {
        return t >= departure[0];
    }

    /** Whether the vehicle has reached the last stop by {@code t}. Call {@link #advanceTo} first. */
    public boolean finished(long t) {
        return segment + 1 == schedule.lastIndex() && t >= arrival[schedule.lastIndex()];
    }

    /** The actual arrival at the last stop; known once the last segment has been sampled. */
    public long endMillis() {
        return arrival[schedule.lastIndex()];
    }

    /**
     * The next moment after {@code t} at which the run changes in a way the emitter must see: the next arrival, or
     * the departure after which the following arrival becomes known. {@code Long.MAX_VALUE} when there is none.
     * Call {@link #advanceTo} with {@code t} first.
     */
    public long nextChangeAfter(long t) {
        int next = segment + 1;
        if (arrival[next] > t) {
            return arrival[next];
        }
        if (next < schedule.lastIndex() && departure[next] > t) {
            return departure[next];
        }
        return Long.MAX_VALUE;
    }

    /** Whether the vehicle reached a stop exactly at {@code t}. Call {@link #advanceTo} with {@code t} first. */
    public boolean arrivesAt(long t) {
        return arrival[segment + 1] == t || (segment > 0 && arrival[segment] == t);
    }

    Period period(int scheduledSeconds) {
        return model.period(this, scheduledSeconds);
    }

    /** The stop the vehicle is heading to or standing at, its status, and where it is along the shape. */
    public Motion motion(long t) {
        if (t < departure[0]) {
            return new Motion(0, VehicleStopStatus.STOPPED_AT, schedule.dist(0), 0, segment);
        }
        int next = segment + 1;
        long from = departure[segment];
        long to = arrival[next];
        if (t < to) {
            double f = (t - from) / (double) (to - from);
            double d0 = schedule.dist(segment);
            double d1 = schedule.dist(next);
            double d = d0 + f * (d1 - d0);
            double speed = (d1 - d0) / ((to - from) / 1000.0);
            boolean incoming = to - t <= INCOMING_MILLIS || d1 - d <= INCOMING_METRES;
            return new Motion(
                    next,
                    incoming ? VehicleStopStatus.INCOMING_AT : VehicleStopStatus.IN_TRANSIT_TO,
                    d,
                    speed,
                    segment);
        }
        return new Motion(next, VehicleStopStatus.STOPPED_AT, schedule.dist(next), 0, segment);
    }

    /** The last stop passed by {@code t}: arrived at, or left for the first stop; -1 before departure. */
    public int passedIndex(long t) {
        for (int j = segment + 1; j >= 1; j--) {
            if (arrival[j] != Long.MIN_VALUE && arrival[j] <= t) {
                return j;
            }
        }
        return departed(t) ? 0 : -1;
    }

    /** Marks everything passed by {@code t} as reported, so the next TripUpdate has no observed part. */
    public void markReported(long t) {
        lastReportedIndex = Math.max(0, passedIndex(t));
    }

    public int lastReportedIndex() {
        return lastReportedIndex;
    }

    /**
     * The stop time updates of a TripUpdate at {@code t} (DOC-25 §6.3): stops passed since the previous update with
     * their observed times, then up to {@code lookahead} predicted stops. Advances {@link #lastReportedIndex()}.
     */
    public List<StopTimeUpdate> tripUpdate(long t, int lookahead) {
        advanceTo(t);
        List<StopTimeUpdate> updates = new ArrayList<>();
        int passed = passedIndex(t);
        for (int j = Math.max(1, lastReportedIndex + 1); j <= passed; j++) {
            StopTimeEvent arrive = event(arrival[j], schedule.arrival(j));
            StopTimeEvent leave = j < schedule.lastIndex() && departure[j] != Long.MIN_VALUE
                    ? event(departure[j], schedule.departure(j))
                    : null;
            updates.add(new StopTimeUpdate(
                    schedule.stopSequence(j), schedule.stop(j).id(), arrive, leave, ScheduleRelationship.SCHEDULED));
        }
        lastReportedIndex = Math.max(lastReportedIndex, passed);

        int first = Math.max(passed + 1, 1);
        int end = Math.min(schedule.lastIndex(), passed + lookahead);
        if (first <= end) {
            long delayMillis = currentDelayMillis(t, passed);
            for (int j = first; j <= end; j++) {
                long predicted = Math.max(scheduledMillis(schedule.arrival(j)) + delayMillis, t + 1);
                updates.add(new StopTimeUpdate(
                        schedule.stopSequence(j),
                        schedule.stop(j).id(),
                        event(predicted, schedule.arrival(j)),
                        null,
                        ScheduleRelationship.SCHEDULED));
            }
        }
        return updates;
    }

    /** Arrival delay while running to the next stop, departure delay while standing at one (DOC-25 §6.3). */
    private long currentDelayMillis(long t, int passed) {
        int next = segment + 1;
        if (passed == next && next < schedule.lastIndex()) {
            return departure[next] - scheduledMillis(schedule.departure(next));
        }
        return arrival[next] - scheduledMillis(schedule.arrival(next));
    }

    private StopTimeEvent event(long millis, int scheduledSeconds) {
        long delay = Math.round((millis - scheduledMillis(scheduledSeconds)) / 1000.0);
        return new StopTimeEvent(Instant.ofEpochMilli(millis), Math.toIntExact(delay));
    }

    private void sample(int i) {
        double departureDelay = (departure[i] - scheduledMillis(schedule.departure(i))) / 1000.0;
        DelayModel.Segment s = model.sample(this, i, departure[i], departureDelay);
        arrival[i + 1] = s.arrivalMillis();
        departure[i + 1] = s.nextDepartureMillis();
        segment = i;
    }

    /** Actual arrival at stop {@code index}; {@code Long.MIN_VALUE} when not sampled yet. */
    public long arrivalMillis(int index) {
        return arrival[index];
    }

    /** Actual departure from stop {@code index}; {@code Long.MIN_VALUE} when not sampled yet. */
    public long departureMillis(int index) {
        return departure[index];
    }

    /**
     * Where a vehicle is on its trip.
     *
     * @param stopIndex the stop it is heading to or standing at
     * @param dist distance along the trip's shape, metres
     * @param speed metres per second along the shape
     * @param segment the segment the vehicle is on or has just finished; its first stop keys occupancy
     */
    public record Motion(int stopIndex, VehicleStopStatus status, double dist, double speed, int segment) {}
}
