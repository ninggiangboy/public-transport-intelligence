package dev.pti.analytics.eta.adapter.out.jdbc;

import dev.pti.analytics.core.domain.ServiceDates.DateRange;
import dev.pti.analytics.eta.application.port.EtaAggregateStore;
import dev.pti.analytics.eta.domain.EtaWindow;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link EtaAggregateStore} on the SQL of DOC-23 §7.1. The aggregation reads {@code fact_trip_update} with a
 * {@code service_date} range, so only the partitions of the 28 days are scanned (§2.1). The statements run in the
 * transaction of the caller; this adapter opens none (DOC-49 §5.1).
 */
public class JdbcEtaAggregateStore implements EtaAggregateStore {

    /** {@code ops.etl_checkpoint.checkpoint_key} of the ETA aggregation (DOC-23 §7.2). */
    static final String CHECKPOINT_KEY = "analytics.eta";

    private static final String ROUTES = """
            SELECT route_id FROM dw.dim_route_current
            UNION
            SELECT route_id FROM insight.insight_eta_prediction
            ORDER BY route_id""";

    // The text of the newest time is taken in UTC, so that it does not depend on the session's time zone.
    private static final String SOURCE_WATERMARK = """
            SELECT count(*)::text || '|'
                   || coalesce((max(coalesce(arrival_time, departure_time)) AT TIME ZONE 'UTC')::text, '-')
            FROM dw.fact_trip_update
            WHERE service_date BETWEEN :fromDate AND :toDate
              AND is_observed""";

    private static final String CHECKPOINT = "SELECT watermark FROM ops.etl_checkpoint WHERE checkpoint_key = :key";

    private static final String SAVE_CHECKPOINT = """
            INSERT INTO ops.etl_checkpoint (checkpoint_key, watermark, watermark_ts, job_execution_id, updated_at)
            VALUES (:key, :watermark, :watermarkTs, :jobExecutionId, now())
            ON CONFLICT (checkpoint_key) DO UPDATE SET
              watermark = excluded.watermark, watermark_ts = excluded.watermark_ts,
              job_execution_id = excluded.job_execution_id, updated_at = excluded.updated_at""";

    /**
     * DOC-23 §7.1 with one addition: the deleted keys are returned like the upserted ones, so that the statement
     * reports what it did. Both data-modifying CTEs run whether or not the final select reads them.
     */
    private static final String RECOMPUTE = """
            WITH obs AS (
              SELECT stop_id,
                     extract(isodow FROM scheduled_arrival AT TIME ZONE :tz)::smallint AS day_of_week,
                     extract(hour   FROM scheduled_arrival AT TIME ZONE :tz)::smallint AS hour_of_day,
                     delay_seconds
              FROM dw.fact_trip_update
              WHERE service_date BETWEEN :fromDate AND :toDate
                AND route_id = :routeId
                AND is_observed AND schedule_relationship = 'SCHEDULED'
                AND delay_seconds IS NOT NULL AND scheduled_arrival IS NOT NULL
                AND coalesce(arrival_time, departure_time) >= :windowFrom
                AND coalesce(arrival_time, departure_time) <  :windowTo
            ),
            agg AS (
              SELECT stop_id, day_of_week, hour_of_day,
                     round(avg(delay_seconds), 1)                                AS avg_delay_seconds,
                     percentile_disc(0.5) WITHIN GROUP (ORDER BY delay_seconds)  AS median_delay_seconds,
                     percentile_disc(0.9) WITHIN GROUP (ORDER BY delay_seconds)  AS p90_delay_seconds,
                     count(*)::int                                               AS sample_count
              FROM obs
              GROUP BY stop_id, day_of_week, hour_of_day
            ),
            upserted AS (
              INSERT INTO insight.insight_eta_prediction AS p (
                route_id, stop_id, day_of_week, hour_of_day, avg_delay_seconds, median_delay_seconds,
                p90_delay_seconds, sample_count, window_start, window_end, computed_at, batch_id)
              SELECT :routeId, stop_id, day_of_week, hour_of_day, avg_delay_seconds, median_delay_seconds,
                     p90_delay_seconds, sample_count, :windowStart, :windowEnd, :computedAt, :batchId
              FROM agg
              ON CONFLICT (route_id, stop_id, day_of_week, hour_of_day) DO UPDATE SET
                avg_delay_seconds = excluded.avg_delay_seconds, median_delay_seconds = excluded.median_delay_seconds,
                p90_delay_seconds = excluded.p90_delay_seconds, sample_count = excluded.sample_count,
                window_start = excluded.window_start, window_end = excluded.window_end,
                computed_at = excluded.computed_at, batch_id = excluded.batch_id
              RETURNING 1
            ),
            deleted AS (
              DELETE FROM insight.insight_eta_prediction p
              WHERE p.route_id = :routeId
                AND NOT EXISTS (SELECT 1 FROM agg a
                                WHERE (a.stop_id, a.day_of_week, a.hour_of_day)
                                    = (p.stop_id, p.day_of_week, p.hour_of_day))
              RETURNING 1
            )
            SELECT (SELECT count(*) FROM upserted)::int AS upserted, (SELECT count(*) FROM deleted)::int AS deleted""";

    private final JdbcClient jdbc;

    public JdbcEtaAggregateStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<String> routeIds() {
        return jdbc.sql(ROUTES).query(String.class).list();
    }

    @Override
    public String sourceWatermark(DateRange serviceDates) {
        return jdbc.sql(SOURCE_WATERMARK)
                .param("fromDate", serviceDates.from())
                .param("toDate", serviceDates.to())
                .query(String.class)
                .single();
    }

    @Override
    public Optional<String> checkpoint() {
        return jdbc.sql(CHECKPOINT)
                .param("key", CHECKPOINT_KEY)
                .query(String.class)
                .optional();
    }

    @Override
    public void saveCheckpoint(String watermark, Instant watermarkTs, @Nullable Long jobExecutionId) {
        jdbc.sql(SAVE_CHECKPOINT)
                .param("key", CHECKPOINT_KEY)
                .param("watermark", watermark)
                .param("watermarkTs", utc(watermarkTs))
                .param("jobExecutionId", jobExecutionId, Types.BIGINT)
                .update();
    }

    @Override
    public Merge recompute(String routeId, EtaWindow window, Instant computedAt, UUID batchId) {
        return jdbc.sql(RECOMPUTE)
                .param("tz", window.zone().getId())
                .param("fromDate", window.serviceDates().from())
                .param("toDate", window.serviceDates().to())
                .param("routeId", routeId)
                .param("windowFrom", utc(window.from()))
                .param("windowTo", utc(window.to()))
                .param("windowStart", window.windowStart())
                .param("windowEnd", window.windowEnd())
                .param("computedAt", utc(computedAt))
                .param("batchId", batchId)
                .query((rs, row) -> new Merge(rs.getInt("upserted"), rs.getInt("deleted")))
                .single();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
