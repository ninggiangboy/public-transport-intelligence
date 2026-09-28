package dev.pti.common.gtfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class GtfsTimeTest {

    private static final ZoneId AGENCY = ZoneId.of("America/Chicago");

    /** The mandatory table of DOC-13 §3; the SQL twin dw.gtfs_time_to_ts must give the same rows. */
    @ParameterizedTest(name = "{0} {1} -> {3}")
    @CsvSource({
        "2026-09-29, 16:16:00, 58560, 2026-09-29T21:16:00Z",
        "2026-09-26, 25:10:00, 90600, 2026-09-27T06:10:00Z",
        "2026-11-01, 01:30:00, 5400, 2026-11-01T07:30:00Z",
        "2026-11-01, 08:00:00, 28800, 2026-11-01T14:00:00Z",
        "2027-03-14, 02:30:00, 9000, 2027-03-14T07:30:00Z",
        "2027-03-14, 08:00:00, 28800, 2027-03-14T13:00:00Z",
    })
    void convertsGtfsTimesFromNoonMinusTwelveHours(String date, String time, int seconds, String utc) {
        assertThat(GtfsTime.parseSeconds(time)).isEqualTo(seconds);
        assertThat(GtfsTime.toInstant(LocalDate.parse(date), seconds, AGENCY)).isEqualTo(Instant.parse(utc));
    }

    @Test
    void parsesSingleDigitHoursAndSurroundingSpaces() {
        assertThat(GtfsTime.parseSeconds("7:05:09")).isEqualTo(7 * 3600 + 5 * 60 + 9);
        assertThat(GtfsTime.parseSeconds(" 24:00:00 ")).isEqualTo(86_400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "16:16", "16:60:00", "16:00:60", "1616:00:00", "-1:00:00", "ab:cd:ef"})
    void rejectsMalformedTimes(String text) {
        assertThatThrownBy(() -> GtfsTime.parseSeconds(text))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Not a GTFS time: '" + text + "'");
    }

    @Test
    void formatsSecondsKeepingHoursPastMidnight() {
        assertThat(GtfsTime.formatSeconds(0)).isEqualTo("00:00:00");
        assertThat(GtfsTime.formatSeconds(90_600)).isEqualTo("25:10:00");
        assertThatThrownBy(() -> GtfsTime.formatSeconds(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesAndFormatsServiceDates() {
        assertThat(GtfsTime.parseServiceDate("20260929")).isEqualTo(LocalDate.of(2026, 9, 29));
        assertThat(GtfsTime.formatServiceDate(LocalDate.of(2026, 10, 3))).isEqualTo("20261003");
    }

    @ParameterizedTest
    @ValueSource(strings = {"20260230", "2026-09-29", "2026929", "20261301"})
    void rejectsInvalidServiceDates(String text) {
        assertThatThrownBy(() -> GtfsTime.parseServiceDate(text))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Not a GTFS date: '" + text + "'");
    }
}
