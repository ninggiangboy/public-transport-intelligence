package dev.pti.api.etlops.domain;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/** The {@code bucket} of {@code GET /etl/jobs/summary} (DOC-32 E-31). */
public enum SummaryBucket {
    ONE_MINUTE("1m", Duration.ofMinutes(1)),
    FIVE_MINUTES("5m", Duration.ofMinutes(5)),
    FIFTEEN_MINUTES("15m", Duration.ofMinutes(15)),
    ONE_HOUR("1h", Duration.ofHours(1));

    private final String wire;
    private final Duration length;

    SummaryBucket(String wire, Duration length) {
        this.wire = wire;
        this.length = length;
    }

    public String wire() {
        return wire;
    }

    public Duration length() {
        return length;
    }

    public static Optional<SummaryBucket> fromWire(String text) {
        return Arrays.stream(values())
                .filter(bucket -> bucket.wire.equals(text))
                .findFirst();
    }
}
