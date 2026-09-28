package dev.pti.common.time;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * The single timestamp format of messages, business keys and payload hashes (DOC-09 §2): RFC 3339 in UTC with
 * exactly three fractional digits and a {@code Z} suffix, e.g. {@code 2026-09-29T21:19:05.000Z}.
 */
public final class Timestamps {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private Timestamps() {}

    /** Formats with millisecond precision; finer precision is truncated. */
    public static String format(Instant instant) {
        return FORMAT.format(instant.truncatedTo(ChronoUnit.MILLIS));
    }

    /**
     * Parses any RFC 3339 timestamp, with {@code Z} or a numeric offset.
     *
     * @throws DateTimeParseException when the text is not RFC 3339
     */
    public static Instant parse(String text) {
        return OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                .toInstant();
    }

    /** Re-formats an RFC 3339 timestamp into the canonical form. */
    public static String normalize(String text) {
        return format(parse(text));
    }
}
