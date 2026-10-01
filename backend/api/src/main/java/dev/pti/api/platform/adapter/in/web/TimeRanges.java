package dev.pti.api.platform.adapter.in.web;

import dev.pti.api.platform.domain.ApiException.FieldError;
import dev.pti.api.platform.domain.ValidationException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Turns the {@code from} and {@code to} parameters of a list endpoint into a range (DOC-31 §4.2, §4.3). A time is
 * ISO-8601 with an offset ({@code 2026-09-29T16:19:30-05:00}; one without is refused), or relative to "now" ({@code
 * -60m}, {@code -6h}, {@code -7d}). The range is {@code [from, to)}; {@code to} defaults to now and {@code from} to
 * {@code to} minus the default span.
 *
 * <p>Which "now" is the axis of the endpoint: the business clock for event time, real time for audit columns (DOC-31
 * §4.2). The platform registers one instance of each ({@code eventTimeRanges}, {@code auditTimeRanges}).
 *
 * <p>A defaulted {@code to} is rounded to the minute, so that requests within the same minute ask for the same range
 * and share a cache entry. {@link #resolve} rounds down, which suits aggregates over finished periods; {@link
 * #resolveThroughNow} rounds up, so that the newest row of a list (an episode that opened a second ago, an alert just
 * raised) is not cut off.
 */
public final class TimeRanges {

    /** A resolved range: {@code from} inclusive, {@code to} exclusive. */
    public record Range(Instant from, Instant to) {}

    private static final Pattern RELATIVE = Pattern.compile("^-(\\d{1,6})([mhd])$");
    private static final Duration MAX_FUTURE = Duration.ofDays(1);

    private final Supplier<Instant> now;
    private final Duration maxRange;

    public TimeRanges(Supplier<Instant> now, Duration maxRange) {
        this.now = now;
        this.maxRange = maxRange;
    }

    /** "Now" on this axis. */
    public Instant now() {
        return now.get();
    }

    /**
     * The range of an aggregate: a defaulted {@code to} is the start of the current minute.
     *
     * @throws ValidationException on {@code from} or {@code to}: not a time, no offset, {@code from} not before {@code
     *     to}, longer than the maximum range, or {@code to} more than a day ahead
     */
    public Range resolve(@Nullable String from, @Nullable String to, Duration defaultSpan) {
        return resolve(from, to, defaultSpan, false);
    }

    /**
     * The range of a list of the newest rows: a defaulted {@code to} is the start of the next minute, so that it
     * includes everything up to now.
     *
     * @throws ValidationException as {@link #resolve}
     */
    public Range resolveThroughNow(@Nullable String from, @Nullable String to, Duration defaultSpan) {
        return resolve(from, to, defaultSpan, true);
    }

    private Range resolve(@Nullable String from, @Nullable String to, Duration defaultSpan, boolean throughNow) {
        Instant current = now.get();
        List<FieldError> errors = new ArrayList<>();
        Instant rounded = current.truncatedTo(ChronoUnit.MINUTES);
        Instant end = to != null
                ? parse("to", to, current, errors)
                : throughNow ? rounded.plus(1, ChronoUnit.MINUTES) : rounded;
        Instant start =
                from == null ? (end != null ? end.minus(defaultSpan) : null) : parse("from", from, current, errors);
        if (end != null && start != null) {
            // A defaulted to that rounds up must not make a range of exactly the maximum too long.
            Instant measuredEnd = to == null && throughNow ? current : end;
            if (!start.isBefore(end)) {
                errors.add(new FieldError("from", "must be before to"));
            } else if (Duration.between(start, measuredEnd).compareTo(maxRange) > 0) {
                errors.add(new FieldError("from", "the range must not be longer than " + maxRange.toDays() + " days"));
            }
            if (end.isAfter(current.plus(MAX_FUTURE))) {
                errors.add(new FieldError("to", "must not be more than one day ahead"));
            }
        }
        if (!errors.isEmpty()) {
            throw new ValidationException("The time range is not valid.", errors);
        }
        return new Range(start, end);
    }

    /**
     * One point in time of a parameter such as {@code since}, in the same two forms as {@code from}. It must not be
     * older than the maximum range or more than a day ahead.
     *
     * @throws ValidationException on {@code field}
     */
    public Instant point(String field, String text) {
        Instant current = now.get();
        List<FieldError> errors = new ArrayList<>();
        Instant value = parse(field, text, current, errors);
        if (value != null) {
            if (Duration.between(value, current).compareTo(maxRange) > 0) {
                errors.add(new FieldError(field, "must not be more than " + maxRange.toDays() + " days ago"));
            } else if (value.isAfter(current.plus(MAX_FUTURE))) {
                errors.add(new FieldError(field, "must not be more than one day ahead"));
            }
        }
        if (!errors.isEmpty() || value == null) {
            throw new ValidationException("The request is not valid.", errors);
        }
        return value;
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
