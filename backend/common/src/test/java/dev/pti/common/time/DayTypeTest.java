package dev.pti.common.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DayTypeTest {

    /** The same days as {@code dw.dim_date} (V6__dim_date.sql). */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "2026-09-29, WEEKDAY",
        "2026-10-03, SATURDAY",
        "2026-10-04, SUNDAY_HOLIDAY",
        "2026-01-01, SUNDAY_HOLIDAY",
        "2026-05-25, SUNDAY_HOLIDAY",
        "2026-05-18, WEEKDAY",
        "2026-07-04, SUNDAY_HOLIDAY",
        "2026-09-07, SUNDAY_HOLIDAY",
        "2026-09-14, WEEKDAY",
        "2026-11-26, SUNDAY_HOLIDAY",
        "2026-11-19, WEEKDAY",
        "2026-12-25, SUNDAY_HOLIDAY",
        "2027-05-31, SUNDAY_HOLIDAY",
        "2027-11-25, SUNDAY_HOLIDAY",
    })
    void classifiesServiceDays(LocalDate date, DayType expected) {
        assertThat(DayType.of(date)).isEqualTo(expected);
    }
}
