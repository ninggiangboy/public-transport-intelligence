package dev.pti.analytics.retention.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * How long {@code insight} rows live (DOC-23 §12.3).
 *
 * @param insight {@code pti.retention.insight}: episodes, anomalies, scorecards and suggestions
 * @param baselineSnapshot {@code pti.retention.baseline-snapshot}: the hourly snapshots a recompute starts from
 * @param agencyZone the zone of the local date that the scorecard's {@code service_date} is compared with
 */
public record RetentionPolicy(Duration insight, Duration baselineSnapshot, ZoneId agencyZone) {

    /** How long an idle route keeps its bunching cursor (DOC-18 §1.1); not configurable. */
    public static final Duration BUNCHING_CURSOR = Duration.ofDays(7);

    public RetentionPolicy {
        Objects.requireNonNull(agencyZone, "agencyZone");
        for (Duration d : new Duration[] {insight, baselineSnapshot}) {
            if (d.isNegative() || d.isZero()) {
                throw new IllegalArgumentException("A retention must be positive: " + d);
            }
        }
    }

    /**
     * The newest expiry value a row may still have and be kept: rows strictly older go.
     *
     * @param businessNow business time now, for the event-time columns and the local date
     * @param realNow real time now, for the audit column
     */
    public RetentionCutoff cutoff(RetentionTarget target, Instant businessNow, Instant realNow) {
        Duration retention = retentionOf(target);
        Instant base = target.basis() == RetentionTarget.Basis.REAL_TIME ? realNow : businessNow;
        LocalDate today = businessNow.atZone(agencyZone).toLocalDate();
        return new RetentionCutoff(base.minus(retention), today.minusDays(retention.toDays()));
    }

    private Duration retentionOf(RetentionTarget target) {
        return switch (target) {
            case BASELINE_SNAPSHOT -> baselineSnapshot;
            case BUNCHING_CURSOR -> BUNCHING_CURSOR;
            case BUNCHING, DISRUPTION, TICKETING_ANOMALY, OTP_SCORECARD, DISPATCH_SUGGESTION -> insight;
        };
    }
}
