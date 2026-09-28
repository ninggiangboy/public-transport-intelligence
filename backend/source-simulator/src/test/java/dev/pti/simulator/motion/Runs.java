package dev.pti.simulator.motion;

import dev.pti.common.gtfs.GtfsTime;
import dev.pti.simulator.feed.Feed;
import dev.pti.simulator.feed.TripSchedule;
import java.time.LocalDate;

/** Builds trip runs for tests. */
final class Runs {

    static final LocalDate TUESDAY = LocalDate.of(2026, 9, 29);

    private Runs() {}

    static DelayModel model(Feed feed, long seed) {
        return new DelayModel(seed, DelayParameters.defaults(), feed.zone());
    }

    static long base(Feed feed, LocalDate serviceDate) {
        return GtfsTime.toInstant(serviceDate, 0, feed.zone()).toEpochMilli();
    }

    static TripRun run(Feed feed, DelayModel model, TripSchedule trip, LocalDate serviceDate) {
        return TripRun.start(trip, serviceDate, base(feed, serviceDate), model, Long.MIN_VALUE);
    }

    /** Samples every segment of the run. */
    static TripRun complete(TripRun run) {
        run.advanceTo(Long.MAX_VALUE / 2);
        return run;
    }
}
