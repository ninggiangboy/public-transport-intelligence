package dev.pti.analytics.retention.domain;

/**
 * The tables of {@code insight} that {@code OpsRetentionJob} empties (DOC-23 §12.3, DOC-18 §1.1), in the order the job
 * does it. {@link #table()} is the label of {@code pti_retention_deleted_total}. The tables that hold running state
 * ({@code analytics_route_baseline}, {@code analytics_bunching_pair_state}) and the ETA table, which is recomputed
 * whole, are not here: nothing in them expires.
 */
public enum RetentionTarget {
    /** Closed episodes whose {@code episode_end} is older than {@code pti.retention.insight}; an open one is kept. */
    BUNCHING("insight.insight_bus_bunching", Basis.BUSINESS_TIME),
    /** Closed episodes, as for bunching. */
    DISRUPTION("insight.insight_service_disruption", Basis.BUSINESS_TIME),
    /** By {@code detected_at}, the end of the window the anomaly was found in: an event time. */
    TICKETING_ANOMALY("insight.insight_ticketing_anomaly", Basis.BUSINESS_TIME),
    /** By {@code service_date}: older than today's local date minus {@code pti.retention.insight}. */
    OTP_SCORECARD("insight.insight_otp_scorecard", Basis.SERVICE_DATE),
    /** By {@code created_at}, a real time written by the database. */
    DISPATCH_SUGGESTION("insight.insight_dispatch_suggestion", Basis.REAL_TIME),
    /** By {@code snapshot_hour} against {@code pti.retention.baseline-snapshot}. */
    BASELINE_SNAPSHOT("insight.analytics_baseline_snapshot", Basis.BUSINESS_TIME),
    /** By {@code last_tick}, only for a route that holds no pair state any more. */
    BUNCHING_CURSOR("insight.analytics_bunching_cursor", Basis.BUSINESS_TIME);

    /** Which time a table's expiry column is measured against. */
    public enum Basis {
        /** An event time: the business clock, which a demo may shift (DR-67). */
        BUSINESS_TIME,
        /** An audit time the database wrote: real time. */
        REAL_TIME,
        /** A local date of the agency, taken from the business clock. */
        SERVICE_DATE
    }

    private final String table;
    private final Basis basis;

    RetentionTarget(String table, Basis basis) {
        this.table = table;
        this.basis = basis;
    }

    public String table() {
        return table;
    }

    public Basis basis() {
        return basis;
    }
}
