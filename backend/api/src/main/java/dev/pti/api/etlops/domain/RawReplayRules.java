package dev.pti.api.etlops.domain;

import dev.pti.api.platform.domain.ApiException.FieldError;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a raw zone replay may ask for (DOC-22 §4.1). The window is on the Kafka record time, which is real time, not
 * event time: the raw zone is partitioned by it. The same rules check E-50 (a violation is a 422) and E-53 (a 400,
 * because it is a GET), so they return the problems and leave the status to the caller.
 *
 * @param maxWindow {@code pti.replay.max-window}, also a CHECK of the table
 * @param rawSettle {@code pti.replay.raw-settle}: the newest record time that may be replayed is now minus this
 * @param rawMaxAge {@code pti.replay.raw-max-age}: the oldest is now minus this
 */
public record RawReplayRules(Duration maxWindow, Duration rawSettle, Duration rawMaxAge) {

    /** The sources of the raw zone: every source but {@code GTFS_STATIC}. */
    public static final Set<String> SOURCES =
            Set.of("GTFS_RT_VEHICLE_POSITION", "GTFS_RT_TRIP_UPDATE", "TICKETING_SALES", "TICKETING_SALE_POINTS");

    /** The values the {@code source} field takes, for the message of an unknown one. */
    public static final List<String> ALL_SOURCES = Arrays.stream(new String[] {
                "GTFS_RT_VEHICLE_POSITION",
                "GTFS_RT_TRIP_UPDATE",
                "TICKETING_SALES",
                "TICKETING_SALE_POINTS",
                "GTFS_STATIC"
            })
            .toList();

    /** Whether the text names a source at all (including {@code GTFS_STATIC}, which has no raw zone). */
    public static boolean isKnownSource(String source) {
        return ALL_SOURCES.contains(source);
    }

    /** The problems of the window, per field; empty when it can be replayed. */
    public List<FieldError> problems(Instant from, Instant to, Instant now) {
        List<FieldError> errors = new ArrayList<>();
        if (!from.isBefore(to)) {
            errors.add(new FieldError("fromTs", "must be before toTs"));
            return errors;
        }
        if (Duration.between(from, to).compareTo(maxWindow) > 0) {
            errors.add(new FieldError("toTs", "the window must not be longer than " + days(maxWindow)));
        }
        if (to.isAfter(now.minus(rawSettle))) {
            errors.add(new FieldError(
                    "toTs",
                    "must be at least " + minutes(rawSettle) + " ago, so that every object of the window is written"));
        }
        if (from.isBefore(now.minus(rawMaxAge))) {
            errors.add(new FieldError("fromTs", "must not be more than " + days(rawMaxAge) + " ago"));
        }
        return errors;
    }

    private static String days(Duration duration) {
        long days = duration.toDays();
        return days + (days == 1 ? " day" : " days");
    }

    private static String minutes(Duration duration) {
        return duration.toMinutes() + " minutes";
    }

    /** The source name as the database has it, or {@code null} when the text is not one. */
    public static String normalise(String source) {
        return source.trim().toUpperCase(Locale.ROOT);
    }
}
