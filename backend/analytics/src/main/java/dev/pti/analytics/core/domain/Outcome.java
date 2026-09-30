package dev.pti.analytics.core.domain;

import java.util.Locale;

/** How a unit of analytics work ended (DOC-23 §14.1). {@link #tag()} is the {@code outcome} metric label. */
public enum Outcome {
    OK,
    NOOP,
    SKIPPED_LOCKED,
    ERROR;

    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
