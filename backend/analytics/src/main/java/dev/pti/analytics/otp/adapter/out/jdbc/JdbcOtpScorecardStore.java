package dev.pti.analytics.otp.adapter.out.jdbc;

import dev.pti.analytics.otp.application.port.OtpScorecardStore;
import dev.pti.analytics.otp.domain.OtpTolerances;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link OtpScorecardStore} on the SQL of DOC-23 §8.1. It reads one {@code service_date} partition of
 * {@code fact_trip_update}; a trip belongs to the day it started, so a trip that ran past midnight is in its own day.
 * The statement runs in the transaction of the caller; this adapter opens none (DOC-49 §5.1).
 */
public class JdbcOtpScorecardStore implements OtpScorecardStore {

    /**
     * DOC-23 §8.1 with one addition: the deleted keys are returned like the upserted ones, so that the statement
     * reports what it did. Both data-modifying CTEs run whether or not the final select reads them.
     */
    private static final String RECOMPUTE = """
            WITH agg AS (
              SELECT route_id,
                     count(*) FILTER (WHERE delay_seconds BETWEEN -:early AND :late) ::int AS on_time_count,
                     count(*) FILTER (WHERE delay_seconds < -:early)                 ::int AS early_count,
                     count(*) FILTER (WHERE delay_seconds >  :late)                  ::int AS late_count,
                     count(*)::int                                                         AS observation_count,
                     count(DISTINCT trip_id)::int                                          AS trip_count
              FROM dw.fact_trip_update
              WHERE service_date = :serviceDate
                AND is_observed AND schedule_relationship = 'SCHEDULED' AND delay_seconds IS NOT NULL
              GROUP BY route_id
            ),
            upserted AS (
              INSERT INTO insight.insight_otp_scorecard AS s (
                route_id, service_date, otp_percentage, on_time_count, early_count, late_count, observation_count,
                trip_count, early_tolerance_seconds, late_tolerance_seconds, computed_at, batch_id)
              SELECT route_id, :serviceDate, round(on_time_count * 100.0 / observation_count, 2), on_time_count,
                     early_count, late_count, observation_count, trip_count, :early, :late, :computedAt, :batchId
              FROM agg
              ON CONFLICT (route_id, service_date) DO UPDATE SET
                otp_percentage = excluded.otp_percentage, on_time_count = excluded.on_time_count,
                early_count = excluded.early_count, late_count = excluded.late_count,
                observation_count = excluded.observation_count, trip_count = excluded.trip_count,
                early_tolerance_seconds = excluded.early_tolerance_seconds,
                late_tolerance_seconds = excluded.late_tolerance_seconds,
                computed_at = excluded.computed_at, batch_id = excluded.batch_id
              RETURNING 1
            ),
            deleted AS (
              DELETE FROM insight.insight_otp_scorecard s
              WHERE s.service_date = :serviceDate
                AND NOT EXISTS (SELECT 1 FROM agg a WHERE a.route_id = s.route_id)
              RETURNING 1
            )
            SELECT (SELECT count(*) FROM upserted)::int AS upserted, (SELECT count(*) FROM deleted)::int AS deleted""";

    private final JdbcClient jdbc;

    public JdbcOtpScorecardStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Merge recompute(LocalDate serviceDate, OtpTolerances tolerances, Instant computedAt, UUID batchId) {
        return jdbc.sql(RECOMPUTE)
                .param("serviceDate", serviceDate)
                .param("early", tolerances.earlySeconds())
                .param("late", tolerances.lateSeconds())
                .param("computedAt", OffsetDateTime.ofInstant(computedAt, ZoneOffset.UTC))
                .param("batchId", batchId)
                .query((rs, row) -> new Merge(rs.getInt("upserted"), rs.getInt("deleted")))
                .single();
    }
}
