package dev.pti.api.etlops.domain;

import java.time.Instant;

/**
 * A row of {@code ops.runtime_flag} (DOC-15 §3, DOC-32 E-55). {@code value} is a JSON scalar: a {@link Boolean}, a
 * {@link Number} or a {@link String}.
 */
public record RuntimeFlag(String key, Object value, String description, String updatedBy, Instant updatedAt) {

    /** The JSON type name of a value, for messages: {@code boolean}, {@code number}, {@code string}, {@code other}. */
    public static String typeOf(Object value) {
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof String) {
            return "string";
        }
        return "other";
    }

    /** True when the proposed value has the same JSON type as the current one (DOC-32 E-57). */
    public boolean acceptsType(Object proposed) {
        return typeOf(value).equals(typeOf(proposed)) && !typeOf(proposed).equals("other");
    }

    /** True when the proposed value is the one the flag has (numbers compare by value). */
    public boolean hasValue(Object proposed) {
        if (value instanceof Number current && proposed instanceof Number next) {
            return new java.math.BigDecimal(current.toString()).compareTo(new java.math.BigDecimal(next.toString()))
                    == 0;
        }
        return value.equals(proposed);
    }
}
