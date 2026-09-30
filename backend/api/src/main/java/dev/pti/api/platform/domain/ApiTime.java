package dev.pti.api.platform.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * How the API writes instants (DOC-31 §4.1): ISO-8601 UTC with a {@code Z} suffix, at most three fraction digits, no
 * fraction when it is zero. Postgres keeps microseconds, so every instant is cut to milliseconds before it is
 * serialized.
 */
public final class ApiTime {

    private ApiTime() {}

    /** Cuts an instant to milliseconds, rounding toward the past. */
    public static Instant truncate(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MILLIS);
    }

    /** {@code 2026-09-29T21:19:30Z} or {@code 2026-09-29T21:19:30.107Z}. */
    public static String format(Instant instant) {
        return truncate(instant).toString();
    }
}
