package dev.pti.etl.dq;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/** The post-write rules of DOC-16 §3 that run on a schedule (scope {@code TABLE}), with their alert threshold. */
public enum PostWriteRule {
    DQ_20("dw.fact_*", Duration.ofHours(1), Threshold.ANY),
    DQ_21("dw.fact_*_default", Duration.ofHours(1), Threshold.ANY),
    DQ_22("dw.fact_ticket_sales", Duration.ofMinutes(5), Threshold.ANY),
    DQ_23("dw.fact_trip_update", Duration.ofHours(1), Threshold.RELATIVE),
    DQ_24("dw.fact_trip_update", Duration.ofHours(1), Threshold.ANY),
    DQ_25("dw.vehicle_position_latest", Duration.ofMinutes(5), Threshold.ANY),
    DQ_26("dw.dim_vehicle", Duration.ofDays(1), Threshold.NEVER);

    /** When a result counts as breached ({@code pti_dq_check_breached}). */
    public enum Threshold {
        /** Any violation. */
        ANY,
        /** More than 0.1% of the rows checked (DQ-23). */
        RELATIVE,
        /** Severity 0: recorded only (DQ-26). */
        NEVER
    }

    static final double RELATIVE_LIMIT = 0.001;

    private final String table;
    private final Duration interval;
    private final Threshold threshold;

    PostWriteRule(String table, Duration interval, Threshold threshold) {
        this.table = table;
        this.interval = interval;
        this.threshold = threshold;
    }

    /** {@code DQ-20}. */
    public String id() {
        return name().replace('_', '-');
    }

    public String table() {
        return table;
    }

    public Duration interval() {
        return interval;
    }

    public boolean breached(long violations, @Nullable Long population) {
        return switch (threshold) {
            case ANY -> violations > 0;
            case RELATIVE -> population != null && population > 0 && violations > RELATIVE_LIMIT * population;
            case NEVER -> false;
        };
    }
}
