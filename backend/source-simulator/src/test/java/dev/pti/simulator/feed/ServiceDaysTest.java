package dev.pti.simulator.feed;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.common.gtfs.GtfsTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ServiceDaysTest {

    private final ServiceDays mini = new ServiceDays(
            Feeds.mini(), new ServiceDateMapper(Feeds.mini().calendar(), "auto"), Duration.ofMinutes(10));

    /** P1-08 acceptance: counts computed by hand from the mini feed's stop_times. */
    @ParameterizedTest(name = "{0} {1} -> {2} trips")
    @CsvSource({
        "2026-09-29, 16:20:00, 8",
        "2026-09-29, 16:40:00, 14",
        "2026-09-29, 17:30:00, 11",
        "2026-10-03, 16:20:00, 6",
        "2026-10-03, 16:40:00, 14",
        "2026-10-03, 17:30:00, 9",
    })
    void countsTheTripsRunningAtAGivenTime(LocalDate date, String time, long expected) {
        ServiceDay day = mini.day(date).orElseThrow();

        assertThat(day.tripsRunningAt(GtfsTime.parseSeconds(time))).isEqualTo(expected);
        assertThat(day.blocks()).hasSize(22);
    }

    @Test
    void considersTheServiceDayBeforeForTripsPastMidnight() {
        Instant at = Instant.parse("2026-09-30T06:30:00Z"); // 01:30 CDT on the 30th

        List<ServiceDay> around = mini.around(at);

        assertThat(around)
                .extracting(ServiceDay::serviceDate)
                .containsExactly(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 30));
        assertThat(mini.secondsOf(around.getFirst(), at)).isEqualTo(25 * 3600 + 30 * 60);
    }

    /** DOC-25 T-03 (block level): about 606 buses and trains are out at 16:40 on a weekday (FR-13.1). */
    @Test
    void aboutSixHundredVehiclesAreOutAtTheAfternoonPeak() {
        ServiceDays real = new ServiceDays(
                Feeds.real(), new ServiceDateMapper(Feeds.real().calendar(), "auto"), Duration.ofMinutes(10));
        Instant at = GtfsTime.toInstant(
                LocalDate.of(2026, 9, 29),
                GtfsTime.parseSeconds("16:40:00"),
                Feeds.real().zone());

        long out = real.around(at).stream()
                .mapToLong(day -> day.blocks().stream()
                        .filter(b -> b.block().start() <= real.secondsOf(day, at)
                                && real.secondsOf(day, at) <= b.block().end())
                        .count())
                .sum();

        assertThat(out).isBetween(576L, 636L);
    }
}
