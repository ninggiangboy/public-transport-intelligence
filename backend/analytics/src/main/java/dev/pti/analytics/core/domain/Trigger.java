package dev.pti.analytics.core.domain;

import java.util.Locale;

/** What started a unit of analytics work (DOC-23 §4). {@link #tag()} is the {@code trigger} metric label. */
public enum Trigger {
    BATCH,
    TICK,
    RECOMPUTE,
    JOB;

    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
