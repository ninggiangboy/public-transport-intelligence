package dev.pti.api.platform.adapter.in.web;

import dev.pti.api.platform.domain.ApiException.FieldError;
import dev.pti.api.platform.domain.NotFoundException;
import dev.pti.api.platform.domain.ValidationException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Parsing and checking of single request values that several controllers share (DOC-31 §6, §9): multi-valued filters,
 * enum values, dates and ids in a path. A wrong value is a 400 on its own field; an id that is not of the right shape
 * is a 404, because for a client it is the same as an id that does not exist.
 */
public final class RequestValues {

    /** The most values a repeated or comma-separated parameter may have (DOC-31 §6). */
    public static final int MAX_VALUES = 20;

    private static final Pattern UUID_TEXT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private RequestValues() {}

    /** The values of a multi-valued parameter, sorted and without duplicates; blank entries are dropped. */
    public static List<String> distinct(String field, @Nullable List<String> values) {
        if (values == null) {
            return List.of();
        }
        TreeSet<String> distinct = new TreeSet<>();
        values.stream().map(String::trim).filter(value -> !value.isEmpty()).forEach(distinct::add);
        if (distinct.size() > MAX_VALUES) {
            throw ValidationException.of(field, "takes at most " + MAX_VALUES + " values");
        }
        return List.copyOf(distinct);
    }

    /** {@link #distinct} with every value one of {@code allowed}; the message names the valid values. */
    public static List<String> allOf(String field, @Nullable List<String> values, Collection<String> allowed) {
        List<String> distinct = distinct(field, values);
        for (String value : distinct) {
            if (!allowed.contains(value)) {
                throw invalidChoice(field, allowed);
            }
        }
        return distinct;
    }

    /** One optional value that must be one of {@code allowed}. */
    public static @Nullable String oneOf(String field, @Nullable String value, Collection<String> allowed) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (!allowed.contains(value)) {
            throw invalidChoice(field, allowed);
        }
        return value;
    }

    /** Integer values, each in {@code [min, max]}, sorted and without duplicates. */
    public static List<Integer> ints(String field, @Nullable List<String> values, int min, int max) {
        return distinct(field, values).stream()
                .map(text -> parseInt(field, text, min, max))
                .distinct()
                .sorted()
                .toList();
    }

    private static int parseInt(String field, String text, int min, int max) {
        try {
            int value = Integer.parseInt(text);
            if (value >= min && value <= max) {
                return value;
            }
        } catch (NumberFormatException e) {
            // reported below, like a value that is out of range
        }
        throw ValidationException.of(field, "must be whole numbers between " + min + " and " + max);
    }

    /** {@code yyyy-MM-dd}. */
    public static LocalDate date(String field, String text) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw ValidationException.of(field, "must be a date such as 2026-09-29");
        }
    }

    /**
     * The id of a path variable.
     *
     * @param what the resource with its article, for the message: {@code "The alert"}
     * @throws NotFoundException when the text is not a UUID
     */
    public static UUID uuidOrNotFound(String text, String what) {
        if (!UUID_TEXT.matcher(text).matches()) {
            throw new NotFoundException(what + " does not exist.");
        }
        return UUID.fromString(text);
    }

    /** An id that came in a query parameter: a malformed one is a 400 on the field. */
    public static UUID uuid(String field, String text) {
        if (!UUID_TEXT.matcher(text).matches()) {
            throw ValidationException.of(field, "must be a UUID");
        }
        return UUID.fromString(text);
    }

    /**
     * The filters of a list as the map {@link CursorCodec#fingerprint} takes: pairs of a name and the value as the
     * client sent it (or {@code null}). Unlike {@code Map.of} it allows the nulls of filters that were not given.
     * Give the raw text of a time parameter, not the resolved instant: a defaulted {@code to} moves with the clock,
     * and a cursor must stay valid while it does.
     */
    public static Map<String, Object> filters(Object... namesAndValues) {
        if (namesAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("Names and values come in pairs");
        }
        Map<String, Object> filters = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            filters.put((String) namesAndValues[i], namesAndValues[i + 1]);
        }
        return filters;
    }

    /** Throws a 400 with every error collected, if there are any. */
    public static void throwIfAny(List<FieldError> errors) {
        if (!errors.isEmpty()) {
            throw new ValidationException("The request is not valid.", errors);
        }
    }

    private static ValidationException invalidChoice(String field, Collection<String> allowed) {
        return ValidationException.of(field, "must be one of " + String.join(", ", allowed));
    }
}
