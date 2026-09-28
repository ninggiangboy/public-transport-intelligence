package dev.pti.common.time;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;

/**
 * The kind of service day, as {@code dw.dim_date.day_type} defines it (DR-11, migration {@code V6__dim_date.sql}):
 * Sundays and the days Metro Transit runs its Sunday schedule are {@code SUNDAY_HOLIDAY}. The holiday rules here
 * must match that migration.
 */
public enum DayType {
    WEEKDAY,
    SATURDAY,
    SUNDAY_HOLIDAY;

    public static DayType of(LocalDate date) {
        if (date.getDayOfWeek() == DayOfWeek.SUNDAY || holiday(date).isPresent()) {
            return SUNDAY_HOLIDAY;
        }
        return date.getDayOfWeek() == DayOfWeek.SATURDAY ? SATURDAY : WEEKDAY;
    }

    /** The holiday on {@code date}, if any. Holidays are not moved to a weekday when they fall on a weekend. */
    public static Optional<String> holiday(LocalDate date) {
        int y = date.getYear();
        if (date.equals(LocalDate.of(y, Month.JANUARY, 1))) {
            return Optional.of("New Year's Day");
        }
        if (date.equals(LocalDate.of(y, Month.MAY, 1).with(TemporalAdjusters.lastInMonth(DayOfWeek.MONDAY)))) {
            return Optional.of("Memorial Day");
        }
        if (date.equals(LocalDate.of(y, Month.JULY, 4))) {
            return Optional.of("Independence Day");
        }
        if (date.equals(LocalDate.of(y, Month.SEPTEMBER, 1).with(TemporalAdjusters.firstInMonth(DayOfWeek.MONDAY)))) {
            return Optional.of("Labor Day");
        }
        if (date.equals(
                LocalDate.of(y, Month.NOVEMBER, 1).with(TemporalAdjusters.dayOfWeekInMonth(4, DayOfWeek.THURSDAY)))) {
            return Optional.of("Thanksgiving Day");
        }
        if (date.equals(LocalDate.of(y, Month.DECEMBER, 25))) {
            return Optional.of("Christmas Day");
        }
        return Optional.empty();
    }
}
