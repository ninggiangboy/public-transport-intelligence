package dev.pti.common.gtfs;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One CSV row of a GTFS file, read by column name (DOC-13 §2.4): empty strings and missing columns are
 * {@code null}.
 */
public final class GtfsRecord {

    private final String file;
    private final long line;
    private final Map<String, Integer> columns;
    private final String[] values;

    GtfsRecord(String file, long line, Map<String, Integer> columns, String[] values) {
        this.file = file;
        this.line = line;
        this.columns = columns;
        this.values = values;
    }

    public @Nullable String get(String column) {
        Integer index = columns.get(column);
        if (index == null || index >= values.length) {
            return null;
        }
        String value = values[index];
        return value.isEmpty() ? null : value;
    }

    /** @throws GtfsFormatException when the value is missing */
    public String require(String column) {
        String value = get(column);
        if (value == null) {
            throw error("missing " + column);
        }
        return value;
    }

    public int requireInt(String column) {
        String value = require(column);
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw error("not an integer in " + column + ": '" + value + "'");
        }
    }

    /** An integer column with a GTFS default for empty values ({@code pickup_type}, {@code location_type}). */
    public int getInt(String column, int defaultValue) {
        return get(column) == null ? defaultValue : requireInt(column);
    }

    public double requireDouble(String column) {
        String value = require(column);
        try {
            return Double.parseDouble(value.strip());
        } catch (NumberFormatException e) {
            throw error("not a number in " + column + ": '" + value + "'");
        }
    }

    /** A decimal column that may be empty; {@code Double.NaN} when it is. */
    public double getDouble(String column) {
        return get(column) == null ? Double.NaN : requireDouble(column);
    }

    /** A GTFS time ({@code HH:MM:SS}, hours may exceed 23) in seconds. */
    public int requireSeconds(String column) {
        String value = require(column);
        try {
            return GtfsTime.parseSeconds(value);
        } catch (IllegalArgumentException e) {
            throw error("bad time in " + column + ": '" + value + "'");
        }
    }

    public GtfsFormatException error(String message) {
        return new GtfsFormatException(file + " line " + line + ": " + message);
    }
}
