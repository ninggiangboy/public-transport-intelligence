package dev.pti.simulator.motion;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.simulator.feed.Feed;
import dev.pti.simulator.feed.Feeds;
import dev.pti.simulator.feed.ServiceDateMapper;
import dev.pti.simulator.feed.ServiceDay;
import dev.pti.simulator.feed.ServiceDays;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** DOC-25 T-07: over a simulated weekday, 70–90% of arrivals are within 5 minutes of schedule (§5.3). */
class DelayCalibrationTest {

    @Test
    void onTimePerformanceOfAWeekdayIsRealistic() {
        Feed feed = Feeds.real();
        ServiceDays days =
                new ServiceDays(feed, new ServiceDateMapper(feed.calendar(), "auto"), Duration.ofMinutes(10));
        DelayModel model = Runs.model(feed, 42);
        ServiceDay day = days.day(Runs.TUESDAY).orElseThrow();
        long base = Runs.base(feed, Runs.TUESDAY);

        long arrivals = 0;
        long onTime = 0;
        for (ServiceDay.AssignedBlock assigned : day.blocks()) {
            VehicleRun vehicle = new VehicleRun(
                    assigned.block(),
                    assigned.vehicleId(),
                    Runs.TUESDAY,
                    base,
                    model,
                    Duration.ofMinutes(30).toMillis(),
                    base + assigned.block().start() * 1000L);
            while (true) {
                TripRun run = Runs.complete(vehicle.run());
                for (int j = 1; j <= run.schedule().lastIndex(); j++) {
                    long delay = run.arrivalMillis(j)
                            - run.scheduledMillis(run.schedule().arrival(j));
                    arrivals++;
                    if (Math.abs(delay) <= 300_000) {
                        onTime++;
                    }
                }
                long after = run.endMillis() + 1;
                vehicle.advanceTo(after);
                if (vehicle.done(after)) {
                    break;
                }
            }
        }

        assertThat(arrivals).isGreaterThan(300_000);
        assertThat((double) onTime / arrivals)
                .as("share of arrivals within 5 minutes")
                .isBetween(0.70, 0.90);
    }
}
