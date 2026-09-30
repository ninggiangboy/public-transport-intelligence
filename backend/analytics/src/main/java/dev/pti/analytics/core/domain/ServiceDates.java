package dev.pti.analytics.core.domain;

import dev.pti.common.gtfs.GtfsTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Service-day arithmetic of DOC-23 §2.1, in the agency time zone. */
public final class ServiceDates {

    private static final long SECONDS_PER_HOUR = 3600;
    private static final int HOURS_PER_DAY = 24;

    private ServiceDates() {}

    /**
     * The hour of the service day that {@code t} falls in, as DOC-14 computes {@code route_headway.hour_of_day}
     * ({@code (departure_seconds / 3600) % 24}): whole hours since GTFS time 0 of {@code serviceDate}, modulo 24. GTFS
     * time 0 is noon minus 12 hours, which differs from local midnight on the days the clocks change.
     */
    public static int hourOfServiceDay(LocalDate serviceDate, Instant t, ZoneId zone) {
        long seconds =
                Duration.between(GtfsTime.toInstant(serviceDate, 0, zone), t).getSeconds();
        return (int) Math.floorMod(Math.floorDiv(seconds, SECONDS_PER_HOUR), (long) HOURS_PER_DAY);
    }

    /**
     * The {@code service_date} range that covers a time interval: {@code localDate(from) − 1 .. localDate(to)}. The
     * fact tables are partitioned by the date a trip starts, so a trip that runs past midnight sits on the day
     * before; the range keeps partition pruning without missing it.
     */
    public static DateRange covering(Instant from, Instant to, ZoneId zone) {
        return new DateRange(
                from.atZone(zone).toLocalDate().minusDays(1), to.atZone(zone).toLocalDate());
    }

    /** An inclusive range of service dates. */
    public record DateRange(LocalDate from, LocalDate to) {

        public DateRange {
            if (to.isBefore(from)) {
                throw new IllegalArgumentException("The range ends before it starts: " + from + " .. " + to);
            }
        }
    }
}
