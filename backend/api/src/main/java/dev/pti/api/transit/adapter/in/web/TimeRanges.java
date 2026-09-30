package dev.pti.api.transit.adapter.in.web;

import dev.pti.api.platform.domain.ApiException.FieldError;
import dev.pti.api.platform.domain.ValidationException;
import dev.pti.common.time.BusinessClock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Turns the {@code from} and {@code to} parameters of an event-time endpoint into a range (DOC-31 §4.2, §4.3). A time is
 * ISO-8601 with an offset ({@code 2026-09-29T16:19:30-05:00}; one without is refused), or relative to business now
 * ({@code -60m}, {@code -6h}, {@code -7d}). The range is {@code [from, to)}; {@code to} defaults to business now and
 * {@code from} to {@code to} minus the default span.
 *
 * <p>A defaulted {@code to} is cut to the minute, so that requests within the same minute ask for the same range and
 * share a cache entry.
 */
public final class TimeRanges {

    /** A resolved range: {@code from} inclusive, {@code to} exclusive. */
    public record Range(Instant from, Instant to) {}

    private static final Pattern RELATIVE = Pattern.compile("^-(\\d{1,6})([mhd])$");
    private static final Duration MAX_FUTURE = Duration.ofDays(1);

    private final BusinessClock clock;
    private final Duration maxRange;

    public TimeRanges(BusinessClock clock, Duration maxRange) {
        this.clock = clock;
        this.maxRange = maxRange;
    }

    /**
     * @throws ValidationException on {@code from} or {@code to}: not a time, no offset, {@code from} not before {@code
     *     to}, longer than the maximum range, or {@code to} more than a day ahead
     */
    public Range resolve(@Nullable String from, @Nullable String to, Duration defaultSpan) {
        Instant now = clock.instant();
        List<FieldError> errors = new ArrayList<>();
        Instant end = to == null ? now.truncatedTo(ChronoUnit.MINUTES) : parse("to", to, now, errors);
        Instant start = from == null ? (end != null ? end.minus(defaultSpan) : null) : parse("from", from, now, errors);
        if (end != null && start != null) {
            if (!start.isBefore(end)) {
                errors.add(new FieldError("from", "must be before to"));
            } else if (Duration.between(start, end).compareTo(maxRange) > 0) {
                errors.add(new FieldError("from", "the range must not be longer than " + maxRange.toDays() + " days"));
            }
            if (end.isAfter(now.plus(MAX_FUTURE))) {
                errors.add(new FieldError("to", "must not be more than one day ahead"));
            }
        }
        if (!errors.isEmpty()) {
            throw new ValidationException("The time range is not valid.", errors);
        }
        return new Range(start, end);
    }

    private static @Nullable Instant parse(String field, String text, Instant now, List<FieldError> errors) {
        Matcher relative = RELATIVE.matcher(text);
        if (relative.matches()) {
            long amount = Long.parseLong(relative.group(1));
            ChronoUnit unit =
                    switch (relative.group(2)) {
                        case "m" -> ChronoUnit.MINUTES;
                        case "h" -> ChronoUnit.HOURS;
                        default -> ChronoUnit.DAYS;
                    };
            return now.minus(amount, unit);
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            errors.add(new FieldError(
                    field, "must be an ISO-8601 time with an offset, such as 2026-09-29T16:19:30-05:00, or like -60m"));
            return null;
        }
    }
}
