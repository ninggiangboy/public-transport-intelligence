package dev.pti.etl.fault;

import java.util.Locale;

/** Where a test or an experiment may inject a failure (DOC-19 §8). */
public enum FaultPoint {
    BEFORE_READ,
    BEFORE_PROCESS,
    AFTER_PROCESS,
    BEFORE_WRITE,
    AFTER_WRITE_BEFORE_COMMIT,
    /** Streaming only: the transaction has committed, the offsets have not. */
    AFTER_COMMIT_BEFORE_ACK;

    /** The property name, e.g. {@code before-write} in {@code pti.test.fault.before-write}. */
    public String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
