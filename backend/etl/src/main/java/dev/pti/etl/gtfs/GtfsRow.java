package dev.pti.etl.gtfs;

import dev.pti.common.gtfs.GtfsTime;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * One data row of a GTFS file with its physical line number, and the conversions of DOC-13 §2.4. Every conversion
 * failure is a {@link GtfsRowException} naming the file, line and column.
 */
public record GtfsRow(String file, int line, Map<String, String> fields) {

    private static final Pattern COLOR = Pattern.compile("[0-9A-F]{6}");

    public GtfsRow {
        fields = Map.copyOf(fields);
    }

    /** True for an empty line, which GTFS producers leave at the end of files. */
    public boolean blank() {
        return fields.values().stream().allMatch(String::isBlank);
    }

    /** The value, or {@code null} when the column is missing or empty (rule 2). */
    public @Nullable String text(String column) {
        String value = fields.get(column);
        return value == null || value.isBlank() ? null : value.strip();
    }

    public String required(String column) {
        String value = text(column);
        if (value == null) {
            throw error(column + " is required");
        }
        return value;
    }

    public @Nullable Integer integer(String column) {
        String value = text(column);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            throw error(column + " is not an integer: '" + value + "'");
        }
    }

    public int requiredInt(String column) {
        Integer value = integer(column);
        if (value == null) {
            throw error(column + " is required");
        }
        return value;
    }

    /** Columns with a GTFS default (location_type, wheelchair_*, pickup_type, drop_off_type): empty is the default. */
    public int intOrDefault(String column, int defaultValue) {
        Integer value = integer(column);
        return value == null ? defaultValue : value;
    }

    public @Nullable Double decimal(String column) {
        String value = text(column);
        if (value == null) {
            return null;
        }
        try {
            return Double.valueOf(value);
        } catch (NumberFormatException e) {
            throw error(column + " is not a number: '" + value + "'");
        }
    }

    public double requiredDecimal(String column) {
        Double value = decimal(column);
        if (value == null) {
            throw error(column + " is required");
        }
        return value;
    }

    public LocalDate date(String column) {
        String value = required(column);
        try {
            return GtfsTime.parseServiceDate(value);
        } catch (IllegalArgumentException e) {
            throw error(column + ": " + e.getMessage());
        }
    }

    /** Seconds after noon minus 12h (DOC-13 §3); may be 86,400 or more. */
    public int seconds(String column) {
        String value = required(column);
        try {
            return GtfsTime.parseSeconds(value);
        } catch (IllegalArgumentException e) {
            throw error(column + ": " + e.getMessage());
        }
    }

    /** Calendar days: {@code 0} or {@code 1}. */
    public boolean flag(String column) {
        String value = required(column);
        return switch (value) {
            case "0" -> false;
            case "1" -> true;
            default -> throw error(column + " is not 0 or 1: '" + value + "'");
        };
    }

    /** {@code timepoint}: 0 is false, empty or 1 is true (rule 5). */
    public boolean timepoint(String column) {
        String value = text(column);
        if (value == null || value.equals("1")) {
            return true;
        }
        if (value.equals("0")) {
            return false;
        }
        throw error(column + " is not 0 or 1: '" + value + "'");
    }

    /** vehicles.txt booleans: {@code True} or {@code False} (rule 6). */
    public @Nullable Boolean bool(String column) {
        String value = text(column);
        if (value == null) {
            return null;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true", "1" -> true;
            case "false", "0" -> false;
            default -> throw error(column + " is not True or False: '" + value + "'");
        };
    }

    /** Colors are upper-cased, because the feed writes them in lower case (rule 4). */
    public @Nullable String color(String column) {
        String value = text(column);
        if (value == null) {
            return null;
        }
        String upper = value.toUpperCase(Locale.ROOT);
        if (!COLOR.matcher(upper).matches()) {
            throw error(column + " is not a hex color: '" + value + "'");
        }
        return upper;
    }

    public GtfsRowException error(String message) {
        return new GtfsRowException(file + " line " + line + ": " + message);
    }
}
