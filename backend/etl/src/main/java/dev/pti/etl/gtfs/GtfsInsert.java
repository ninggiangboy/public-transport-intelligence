package dev.pti.etl.gtfs;

import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/** The parameters of one INSERT, with the line it came from for the validation report. */
public record GtfsInsert(String file, int line, MapSqlParameterSource params) {

    public static GtfsInsert of(GtfsRow row, Map<String, ?> values) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        values.forEach(params::addValue);
        return new GtfsInsert(row.file(), row.line(), params);
    }
}
