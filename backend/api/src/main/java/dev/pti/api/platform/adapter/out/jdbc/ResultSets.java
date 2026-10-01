package dev.pti.api.platform.adapter.out.jdbc;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Reading and binding the column types the API's repositories share: instants at full microsecond precision (a keyset
 * cursor must compare exactly what the database stores; the response cuts to milliseconds later, {@code ApiTime}),
 * nullable numbers and text arrays.
 */
public final class ResultSets {

    private ResultSets() {}

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        if (value == null) {
            throw new IllegalStateException("Column " + column + " is null but the query promised a value");
        }
        return value.toInstant();
    }

    public static @Nullable Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value != null ? value.toInstant() : null;
    }

    public static @Nullable Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    public static @Nullable BigDecimal nullableDecimal(ResultSet rs, String column) throws SQLException {
        return rs.getBigDecimal(column);
    }

    public static UUID uuid(ResultSet rs, String column) throws SQLException {
        UUID value = rs.getObject(column, UUID.class);
        if (value == null) {
            throw new IllegalStateException("Column " + column + " is null but the query promised a value");
        }
        return value;
    }

    public static @Nullable UUID nullableUuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    /** A {@code text[]} column; never null. */
    public static List<String> textArray(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        try {
            return Arrays.asList((String[]) array.getArray());
        } finally {
            array.free();
        }
    }

    /** An instant as the {@code timestamptz} parameter the drivers accept. */
    public static OffsetDateTime timestamp(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    public static @Nullable OffsetDateTime nullableTimestamp(@Nullable Instant instant) {
        return instant == null ? null : timestamp(instant);
    }
}
