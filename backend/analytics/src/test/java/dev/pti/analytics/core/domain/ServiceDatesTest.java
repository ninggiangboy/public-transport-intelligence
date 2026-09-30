package dev.pti.analytics.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** DOC-23 §2.1: hour of the service day and the service-date range of a time interval. */
class ServiceDatesTest {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

    @Test
    void anAfternoonInstantIsItsLocalHour() {
        // 21:19:31Z is 16:19:31 in Chicago (CDT).
        assertThat(ServiceDates.hourOfServiceDay(
                        LocalDate.parse("2026-09-29"), Instant.parse("2026-09-29T21:19:31Z"), CHICAGO))
                .isEqualTo(16);
    }

    @Test
    void anInstantAfterMidnightOfTheTripDayCountsPastTwentyThreeAndWraps() {
        // 05:30Z on the 29th is 00:30 local: 24.5 hours into the service day of the 28th, so hour 0 again.
        assertThat(ServiceDates.hourOfServiceDay(
                        LocalDate.parse("2026-09-28"), Instant.parse("2026-09-29T05:30:00Z"), CHICAGO))
                .isZero();
    }

    @Test
    void theHourFollowsGtfsTimeNotTheWallClockOnTheDayTheClocksGoBack() {
        // 2026-11-01: GTFS time 0 is 06:00Z (noon minus 12 h), an hour after local midnight (05:00Z).
        LocalDate serviceDate = LocalDate.parse("2026-11-01");

        assertThat(ServiceDates.hourOfServiceDay(serviceDate, Instant.parse("2026-11-01T06:30:00Z"), CHICAGO))
                .isZero();
        assertThat(ServiceDates.hourOfServiceDay(serviceDate, Instant.parse("2026-11-01T07:00:00Z"), CHICAGO))
                .isEqualTo(1);
    }

    @Test
    void anInstantBeforeGtfsTimeZeroWrapsToTheLastHour() {
        assertThat(ServiceDates.hourOfServiceDay(
                        LocalDate.parse("2026-11-01"), Instant.parse("2026-11-01T05:30:00Z"), CHICAGO))
                .isEqualTo(23);
    }

    @Test
    void theDateRangeStartsOneDayBeforeTheLocalDateOfTheStart() {
        ServiceDates.DateRange range = ServiceDates.covering(
                Instant.parse("2026-09-29T05:30:00Z"), Instant.parse("2026-09-29T21:00:00Z"), CHICAGO);

        assertThat(range.from()).isEqualTo(LocalDate.parse("2026-09-28"));
        assertThat(range.to()).isEqualTo(LocalDate.parse("2026-09-29"));
    }

    @Test
    void theLocalDateNotTheUtcDateDecidesTheRange() {
        // 03:00Z on the 30th is still the evening of the 29th in Chicago.
        ServiceDates.DateRange range = ServiceDates.covering(
                Instant.parse("2026-09-30T02:00:00Z"), Instant.parse("2026-09-30T03:00:00Z"), CHICAGO);

        assertThat(range.from()).isEqualTo(LocalDate.parse("2026-09-28"));
        assertThat(range.to()).isEqualTo(LocalDate.parse("2026-09-29"));
    }

    @Test
    void aRangeMayNotEndBeforeItStarts() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> new ServiceDates.DateRange(LocalDate.parse("2026-09-29"), LocalDate.parse("2026-09-28")));
    }
}
