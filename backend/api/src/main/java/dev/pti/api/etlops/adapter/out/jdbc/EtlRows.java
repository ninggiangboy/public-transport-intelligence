package dev.pti.api.etlops.adapter.out.jdbc;

import dev.pti.api.platform.domain.KeysetCursor;
import dev.pti.api.platform.domain.ValidationException;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Reading columns, binding parameters and the JSON and cursor keys that the JDBC adapters of {@code etlops} share. */
final class EtlRows {

    private final JsonMapper mapper;

    EtlRows(JsonMapper mapper) {
        this.mapper = mapper;
    }

    static @Nullable Long longValue(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    /** The elements of a {@code uuid[]} column as text; empty for {@code NULL}. */
    static List<String> uuidList(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object element : (Object[]) array.getArray()) {
            values.add(String.valueOf(element));
        }
        return values;
    }

    /** A JSON object column read as {@code text}, as a map; empty for {@code NULL}. */
    Map<String, Object> object(@Nullable String json) {
        if (json == null) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = mapper.readValue(json, new tools.jackson.core.type.TypeReference<>() {});
            return parsed == null ? Map.of() : new LinkedHashMap<>(parsed);
        } catch (JacksonException e) {
            throw new IllegalStateException("A JSON column does not hold an object", e);
        }
    }

    /** A JSON scalar column read as {@code text}: a boolean, number or string. */
    Object scalar(String json) {
        try {
            return mapper.readValue(json, Object.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("A JSON column does not hold a value", e);
        }
    }

    /** Writes a value as JSON text, for a {@code CAST(:x AS jsonb)}. */
    String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("A value cannot be written as JSON", e);
        }
    }

    /** The key of a cursor as an instant: the first key. */
    static Instant instantKey(KeysetCursor cursor, int index) {
        try {
            return Instant.parse(cursor.keys().get(index));
        } catch (DateTimeParseException | IndexOutOfBoundsException e) {
            throw invalidCursor();
        }
    }

    static String textKey(KeysetCursor cursor, int index) {
        if (index >= cursor.keys().size()) {
            throw invalidCursor();
        }
        return cursor.keys().get(index);
    }

    static long longKey(KeysetCursor cursor, int index) {
        try {
            return Long.parseLong(textKey(cursor, index));
        } catch (NumberFormatException e) {
            throw invalidCursor();
        }
    }

    private static ValidationException invalidCursor() {
        return ValidationException.of("cursor", "is not a valid cursor");
    }

    /** The keys of a page's last row for a cursor that sorts by an instant and then an id. */
    static List<String> keys(Instant at, Object id) {
        return List.of(at.toString(), String.valueOf(id));
    }
}
