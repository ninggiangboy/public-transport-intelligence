package dev.pti.simulator.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** DOC-25 T-01. */
class ServiceDateMapperTest {

    @Test
    void fixedAlwaysGivesTheSameDate() {
        ServiceDateMapper mapper = new ServiceDateMapper(Feeds.mini().calendar(), "fixed:2026-09-29");

        assertThat(mapper.feedDateFor(LocalDate.of(2031, 1, 4))).contains(LocalDate.of(2026, 9, 29));
    }

    @Test
    void autoKeepsDatesInsideTheFeed() {
        ServiceDateMapper mapper = new ServiceDateMapper(Feeds.real().calendar(), "auto");

        assertThat(mapper.feedDateFor(LocalDate.of(2026, 10, 15))).contains(LocalDate.of(2026, 10, 15));
    }

    @Test
    void autoMapsOutsideDatesToTheRegularDayOfTheSameWeekday() {
        ServiceDateMapper mapper = new ServiceDateMapper(Feeds.real().calendar(), "auto");

        assertThat(mapper.feedDateFor(LocalDate.of(2026, 12, 15))).contains(LocalDate.of(2026, 9, 29)); // Tuesday
        assertThat(mapper.feedDateFor(LocalDate.of(2027, 1, 2))).contains(LocalDate.of(2026, 10, 3)); // Saturday
        assertThat(mapper.feedDateFor(LocalDate.of(2026, 9, 20))).contains(LocalDate.of(2026, 10, 4)); // Sunday
    }

    @Test
    void skipsHolidaysWhenMapping() {
        // Mondays 5 Oct to 26 Oct; the first one drops service 1 (a holiday), so it is in a group of one.
        ServiceCalendar calendar = new ServiceCalendar(
                Map.of(
                        "1",
                                new ServiceCalendar.Weekly(
                                        EnumSet.of(DayOfWeek.MONDAY),
                                        LocalDate.of(2026, 10, 5),
                                        LocalDate.of(2026, 10, 26)),
                        "2",
                                new ServiceCalendar.Weekly(
                                        EnumSet.of(DayOfWeek.MONDAY),
                                        LocalDate.of(2026, 10, 5),
                                        LocalDate.of(2026, 10, 26))),
                Map.of(LocalDate.of(2026, 10, 5), Map.of("1", false)));
        ServiceDateMapper mapper = new ServiceDateMapper(calendar, "auto");

        assertThat(mapper.feedDateFor(LocalDate.of(2026, 11, 2))).contains(LocalDate.of(2026, 10, 12));
        assertThat(mapper.feedDateFor(LocalDate.of(2026, 11, 3))).isEmpty();
    }

    @Test
    void rejectsOtherModes() {
        assertThatThrownBy(() -> new ServiceDateMapper(Feeds.mini().calendar(), "latest"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
