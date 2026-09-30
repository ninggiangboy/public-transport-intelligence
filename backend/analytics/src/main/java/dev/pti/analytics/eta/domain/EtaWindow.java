package dev.pti.analytics.eta.domain;

import dev.pti.analytics.core.domain.ServiceDates;
import dev.pti.analytics.core.domain.ServiceDates.DateRange;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The window of one ETA aggregation (DOC-23 §7.1): the samples whose {@code observed_at} lies in
 * {@code [hour − window, hour)}, read from the partitions of {@link #serviceDates()}. Every bound is a function of the
 * hour and the window, so running the same hour again reads the same samples.
 *
 * @param hour the run's hour {@code H}: the window ends here and {@code computed_at} is this instant
 * @param from first instant of the window, inclusive ({@code H − window})
 * @param to end of the window, exclusive ({@code H})
 * @param serviceDates the {@code service_date} range that covers the window, one day before its start included so that
 *     a trip that started before midnight is not missed (§2.1)
 * @param windowStart local date of the first instant, stored as {@code window_start}
 * @param windowEnd local date of the last instant ({@code H − 1s}), stored as {@code window_end}
 * @param zone the agency time zone that the day of the week and the hour of a sample are read in
 */
public record EtaWindow(
        Instant hour,
        Instant from,
        Instant to,
        DateRange serviceDates,
        LocalDate windowStart,
        LocalDate windowEnd,
        ZoneId zone) {

    public static EtaWindow of(Instant hour, Duration window, ZoneId zone) {
        Instant from = hour.minus(window);
        return new EtaWindow(
                hour,
                from,
                hour,
                ServiceDates.covering(from, hour, zone),
                from.atZone(zone).toLocalDate(),
                hour.minusSeconds(1).atZone(zone).toLocalDate(),
                zone);
    }
}
