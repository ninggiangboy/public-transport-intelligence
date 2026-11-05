package dev.pti.etl.write.domain;

import java.util.Locale;

/** Label {@code mode} of the ETL metrics (DOC-28): who runs the chunk. */
public enum RunMode {
    STREAM,
    BATCH;

    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
