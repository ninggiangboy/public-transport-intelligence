package dev.pti.etl.write;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * Parameters with explicit SQL types. Without a type, a {@code null} makes Spring ask the driver for parameter
 * metadata, one extra round trip per statement.
 */
final class SqlParams {

    private final MapSqlParameterSource source = new MapSqlParameterSource();

    private SqlParams() {}

    static SqlParams of() {
        return new SqlParams();
    }

    SqlParams text(String name, @Nullable String value) {
        source.addValue(name, value, Types.VARCHAR);
        return this;
    }

    SqlParams date(String name, LocalDate value) {
        source.addValue(name, value, Types.DATE);
        return this;
    }

    SqlParams timestamp(String name, @Nullable Instant value) {
        source.addValue(name, value == null ? null : value.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
        return this;
    }

    SqlParams smallint(String name, short value) {
        source.addValue(name, value, Types.SMALLINT);
        return this;
    }

    SqlParams integer(String name, @Nullable Integer value) {
        source.addValue(name, value, Types.INTEGER);
        return this;
    }

    SqlParams bigint(String name, long value) {
        source.addValue(name, value, Types.BIGINT);
        return this;
    }

    SqlParams real4(String name, @Nullable Float value) {
        source.addValue(name, value, Types.REAL);
        return this;
    }

    SqlParams real8(String name, double value) {
        source.addValue(name, value, Types.DOUBLE);
        return this;
    }

    SqlParams numeric(String name, BigDecimal value) {
        source.addValue(name, value, Types.NUMERIC);
        return this;
    }

    SqlParams bool(String name, boolean value) {
        source.addValue(name, value, Types.BOOLEAN);
        return this;
    }

    SqlParams uuid(String name, @Nullable UUID value) {
        source.addValue(name, value, Types.OTHER);
        return this;
    }

    MapSqlParameterSource build() {
        return source;
    }
}
