package dev.pti.api.transit.adapter.in.web;

import dev.pti.api.platform.domain.ApiException.FieldError;
import dev.pti.api.platform.domain.ApiTime;
import dev.pti.api.platform.domain.ValidationException;
import dev.pti.api.transit.domain.BoundingBox;
import dev.pti.api.transit.domain.BucketSize;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/** Parsing and checking of the query parameters of the transit endpoints (DOC-32 §3, DOC-31 §6). */
final class TransitParams {

    /** The most values a repeated or comma-separated parameter may have (DOC-31 §6). */
    static final int MAX_VALUES = 20;

    private TransitParams() {}

    /** An instant as the API writes it ({@code ApiTime}), or {@code null} when there is none. */
    static @Nullable String instant(@Nullable Instant instant) {
        return instant != null ? ApiTime.format(instant) : null;
    }

    /** A date as {@code yyyy-MM-dd}, or {@code null} when there is none. */
    static @Nullable String date(@Nullable LocalDate date) {
        return date != null ? date.toString() : null;
    }

    /** The values of a multi-valued parameter, without duplicates; absent and blank entries are dropped. */
    static List<String> values(String field, @Nullable List<String> values) {
        if (values == null) {
            return List.of();
        }
        TreeSet<String> distinct = new TreeSet<>();
        values.stream().map(String::trim).filter(value -> !value.isEmpty()).forEach(distinct::add);
        checkCount(field, distinct.size());
        return List.copyOf(distinct);
    }

    static void checkCount(String field, int count) {
        if (count > MAX_VALUES) {
            throw ValidationException.of(field, "takes at most " + MAX_VALUES + " values");
        }
    }

    /** {@code hour} (default), {@code day} or {@code hour-of-week}. */
    static BucketSize bucket(@Nullable String value) {
        if (value == null) {
            return BucketSize.HOUR;
        }
        return switch (value) {
            case "hour" -> BucketSize.HOUR;
            case "day" -> BucketSize.DAY;
            case "hour-of-week" -> BucketSize.HOUR_OF_WEEK;
            default -> throw ValidationException.of("bucket", "must be one of hour, day, hour-of-week");
        };
    }

    /** The wire name of a bucket size: the lowercase, hyphenated form the parameter takes. */
    static String wire(BucketSize bucket) {
        return bucket.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** {@code minLon,minLat,maxLon,maxLat}, with the minimum below the maximum and an area of at most 0.25 deg2. */
    static BoundingBox bbox(String value) {
        String[] parts = value.split(",", -1);
        if (parts.length != 4) {
            throw ValidationException.of("bbox", "must be minLon,minLat,maxLon,maxLat");
        }
        double[] numbers = new double[4];
        for (int i = 0; i < 4; i++) {
            try {
                numbers[i] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException e) {
                throw ValidationException.of("bbox", "must be four numbers: minLon,minLat,maxLon,maxLat");
            }
            if (!Double.isFinite(numbers[i])) {
                throw ValidationException.of("bbox", "must be four finite numbers");
            }
        }
        boolean lonOk = inRange(numbers[0], 180) && inRange(numbers[2], 180);
        boolean latOk = inRange(numbers[1], 90) && inRange(numbers[3], 90);
        if (!lonOk || !latOk) {
            throw ValidationException.of("bbox", "longitudes are -180..180 and latitudes -90..90");
        }
        if (!(numbers[0] < numbers[2]) || !(numbers[1] < numbers[3])) {
            throw ValidationException.of("bbox", "the minimum must be below the maximum");
        }
        BoundingBox box = new BoundingBox(numbers[0], numbers[1], numbers[2], numbers[3]);
        if (box.area() > BoundingBox.MAX_AREA_SQUARE_DEGREES) {
            throw ValidationException.of(
                    "bbox", "must cover at most " + BoundingBox.MAX_AREA_SQUARE_DEGREES + " square degrees");
        }
        return box;
    }

    private static boolean inRange(double value, double limit) {
        return value >= -limit && value <= limit;
    }

    /** An ISO-8601 duration such as {@code PT90M}. */
    static Duration duration(String field, String value) {
        try {
            return Duration.parse(value);
        } catch (DateTimeParseException e) {
            throw ValidationException.of(field, "must be an ISO-8601 duration such as PT90M");
        }
    }

    /** {@code 0} or {@code 1}. */
    static void checkDirection(@Nullable Integer directionId, List<FieldError> errors) {
        if (directionId != null && directionId != 0 && directionId != 1) {
            errors.add(new FieldError("directionId", "must be 0 or 1"));
        }
    }

    static void checkRange(String field, @Nullable Integer value, int min, int max, List<FieldError> errors) {
        if (value != null && (value < min || value > max)) {
            errors.add(new FieldError(field, "must be between " + min + " and " + max));
        }
    }

    static void throwIfAny(List<FieldError> errors) {
        if (!errors.isEmpty()) {
            throw new ValidationException("The request is not valid.", new ArrayList<>(errors));
        }
    }
}
