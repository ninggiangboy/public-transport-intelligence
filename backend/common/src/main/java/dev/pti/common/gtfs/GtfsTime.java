package dev.pti.common.gtfs;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GTFS times and service dates (DR-09, DOC-13 §3). A GTFS time is a number of seconds counted from
 * "noon minus 12 hours" of the service date in the agency time zone, not from local midnight: the two differ on
 * the days clocks change. Times may exceed 24:00:00 for trips that run past midnight.
 */
public final class GtfsTime {

    private static final Pattern TIME = Pattern.compile("^\\s*(\\d{1,3}):([0-5]\\d):([0-5]\\d)\\s*$");
    private static final DateTimeFormatter SERVICE_DATE =
            DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

    private GtfsTime() {}

    /** The instant of a GTFS time on a service date; the SQL twin is {@code dw.gtfs_time_to_ts}. */
    public static Instant toInstant(LocalDate serviceDate, int seconds, ZoneId zone) {
        return serviceDate
                .atTime(LocalTime.NOON)
                .atZone(zone)
                .toInstant()
                .minus(Duration.ofHours(12))
                .plusSeconds(seconds);
    }

    /**
     * Parses {@code H:MM:SS} or {@code HH:MM:SS} (hours may be 24 or more) into seconds.
     *
     * @throws IllegalArgumentException when the text is not a GTFS time
     */
    public static int parseSeconds(String text) {
        Matcher m = TIME.matcher(text);
        if (!m.matches()) {
            throw new IllegalArgumentException("Not a GTFS time: '" + text + "'");
        }
        return Integer.parseInt(m.group(1)) * 3600 + Integer.parseInt(m.group(2)) * 60 + Integer.parseInt(m.group(3));
    }

    /** Formats seconds as {@code HH:MM:SS}, keeping hours of 24 and more. */
    public static String formatSeconds(int seconds) {
        if (seconds < 0) {
            throw new IllegalArgumentException("GTFS time cannot be negative: " + seconds);
        }
        return "%02d:%02d:%02d".formatted(seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    /**
     * Parses a GTFS date ({@code YYYYMMDD}), as used by {@code start_date} in messages.
     *
     * @throws IllegalArgumentException when the text is not a valid date
     */
    public static LocalDate parseServiceDate(String text) {
        try {
            return LocalDate.parse(text, SERVICE_DATE);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Not a GTFS date: '" + text + "'", e);
        }
    }

    public static String formatServiceDate(LocalDate date) {
        return SERVICE_DATE.format(date);
    }
}
