package dev.pti.etl.gtfs.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** The parameters of one INSERT, with the line it came from for the validation report. */
public record GtfsInsert(String file, int line, Map<String, ?> values) {

    public GtfsInsert {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public static GtfsInsert of(GtfsRow row, Map<String, ?> values) {
        return new GtfsInsert(row.file(), row.line(), values);
    }
}
