package dev.pti.etl.fault;

import java.util.Locale;

/** What an armed {@link FaultPoint} does (DOC-19 §8). */
public enum FaultAction {
    THROW_TRANSIENT,
    THROW_FATAL,
    /** {@code Runtime.halt(137)}: a {@code kill -9} from the inside, no shutdown hook, no further commit. */
    HALT;

    public static FaultAction parse(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }
}
