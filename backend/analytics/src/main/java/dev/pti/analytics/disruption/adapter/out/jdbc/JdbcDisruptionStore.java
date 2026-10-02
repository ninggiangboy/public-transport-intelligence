package dev.pti.analytics.disruption.adapter.out.jdbc;

import dev.pti.analytics.core.domain.ServiceDates.DateRange;
import dev.pti.analytics.disruption.application.port.DisruptionStore;
import dev.pti.analytics.disruption.domain.Arrival;
import dev.pti.analytics.disruption.domain.BaselineState;
import dev.pti.analytics.disruption.domain.CloseReason;
import dev.pti.analytics.disruption.domain.DisruptionEpisode;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link DisruptionStore} on the SQL of DOC-23 §6. The reads of {@code fact_trip_update} always carry a
 * {@code service_date} range so that only the partitions of the window are scanned (§2.1). The statements run in the
 * transaction of the caller; this adapter opens none (DOC-49 §5.1).
 */
public class JdbcDisruptionStore implements DisruptionStore {

    private static final String CURSOR =
            "SELECT min(last_bucket) FROM insight.analytics_route_baseline WHERE route_id = :routeId";

    /**
     * One service date at a time. With {@code service_date} fixed the planner reads the route's rows through
     * {@code (service_date, route_id)}; over a {@code BETWEEN} that covers whole partitions it scans every row of the
     * day instead. {@code event_timestamp} changes on every update, so it stays out of the index (DR-65).
     */
    private static final String NEWEST_UPDATE = """
            SELECT max(event_timestamp)
            FROM dw.fact_trip_update
            WHERE service_date = :serviceDate
              AND route_id = :routeId""";

    private static final String BASELINES = """
            SELECT direction_id, ewma_mean, ewma_var, bucket_count, last_bucket, consecutive_high, consecutive_low,
                   open_episode_id
            FROM insight.analytics_route_baseline
            WHERE route_id = :routeId""";

    private static final String OPEN_EPISODE = """
            SELECT id, route_id, direction_id, episode_start, episode_end, close_reason, baseline_mean_seconds,
                   baseline_stddev_seconds, current_avg_delay_seconds, current_z_score, peak_avg_delay_seconds,
                   peak_z_score, sample_count, affected_stop_ids, last_bucket
            FROM insight.insight_service_disruption
            WHERE id = :id AND status = 'OPEN'""";

    private static final String ARRIVALS = """
            SELECT direction_id, stop_id, coalesce(arrival_time, departure_time) AS observed_at, delay_seconds
            FROM dw.fact_trip_update
            WHERE service_date BETWEEN :fromDate AND :toDate
              AND route_id = :routeId
              AND is_observed AND schedule_relationship = 'SCHEDULED' AND delay_seconds IS NOT NULL
              AND coalesce(arrival_time, departure_time) >= :from
              AND coalesce(arrival_time, departure_time) < :to""";

    private static final String HAS_ARRIVALS = """
            SELECT EXISTS (
              SELECT 1
              FROM dw.fact_trip_update
              WHERE service_date BETWEEN :fromDate AND :toDate
                AND route_id = :routeId
                AND is_observed AND schedule_relationship = 'SCHEDULED' AND delay_seconds IS NOT NULL
                AND coalesce(arrival_time, departure_time) >= :from
                AND coalesce(arrival_time, departure_time) < :to)""";

    private static final String SAVE_BASELINE = """
            INSERT INTO insight.analytics_route_baseline (route_id, direction_id, ewma_mean, ewma_var, bucket_count,
                                                          last_bucket, consecutive_high, consecutive_low,
                                                          open_episode_id)
            VALUES (:routeId, :directionId, :mean, :variance, :bucketCount, :lastBucket, :consecutiveHigh,
                    :consecutiveLow, :openEpisodeId)
            ON CONFLICT (route_id, direction_id) DO UPDATE SET
              ewma_mean = excluded.ewma_mean, ewma_var = excluded.ewma_var, bucket_count = excluded.bucket_count,
              last_bucket = excluded.last_bucket, consecutive_high = excluded.consecutive_high,
              consecutive_low = excluded.consecutive_low, open_episode_id = excluded.open_episode_id,
              updated_at = now()""";

    private static final String SAVE_SNAPSHOT = """
            INSERT INTO insight.analytics_baseline_snapshot (snapshot_hour, route_id, direction_id, ewma_mean, ewma_var,
                                                             bucket_count, last_bucket, consecutive_high,
                                                             consecutive_low, open_episode_id)
            VALUES (:snapshotHour, :routeId, :directionId, :mean, :variance, :bucketCount, :lastBucket,
                    :consecutiveHigh, :consecutiveLow, :openEpisodeId)
            ON CONFLICT (snapshot_hour, route_id, direction_id) DO UPDATE SET
              ewma_mean = excluded.ewma_mean, ewma_var = excluded.ewma_var, bucket_count = excluded.bucket_count,
              last_bucket = excluded.last_bucket, consecutive_high = excluded.consecutive_high,
              consecutive_low = excluded.consecutive_low, open_episode_id = excluded.open_episode_id""";

    /** The enrichment columns are not in the statement: they keep their defaults on insert and triage's values. */
    private static final String SAVE_EPISODE = """
            INSERT INTO insight.insight_service_disruption (
              id, route_id, direction_id, episode_start, episode_end, status, close_reason, baseline_mean_seconds,
              baseline_stddev_seconds, current_avg_delay_seconds, current_z_score, peak_avg_delay_seconds,
              peak_z_score, sample_count, affected_stop_ids, last_bucket, batch_id)
            VALUES (
              :id, :routeId, :directionId, :episodeStart, :episodeEnd, :status, :closeReason, :baselineMean,
              :baselineStddev, :currentAvg, :currentZ, :peakAvg, :peakZ, :sampleCount, :affectedStopIds, :lastBucket,
              :batchId)
            ON CONFLICT (id) DO UPDATE SET
              baseline_mean_seconds = excluded.baseline_mean_seconds,
              baseline_stddev_seconds = excluded.baseline_stddev_seconds,
              current_avg_delay_seconds = excluded.current_avg_delay_seconds,
              current_z_score = excluded.current_z_score,
              peak_avg_delay_seconds = excluded.peak_avg_delay_seconds,
              peak_z_score = excluded.peak_z_score,
              sample_count = excluded.sample_count,
              affected_stop_ids = excluded.affected_stop_ids,
              last_bucket = excluded.last_bucket,
              episode_end = excluded.episode_end,
              status = excluded.status,
              close_reason = excluded.close_reason,
              batch_id = excluded.batch_id,
              updated_at = now()""";

    private static final String ROUTES_NEEDING_TICK = """
            SELECT DISTINCT route_id
            FROM insight.analytics_route_baseline
            WHERE open_episode_id IS NOT NULL OR consecutive_high > 0 OR consecutive_low > 0
            ORDER BY route_id""";

    private final JdbcClient jdbc;

    public JdbcDisruptionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Instant> cursor(String routeId) {
        // An aggregate over no rows is one row holding NULL, which a row mapper may not return as a bare null.
        return jdbc.sql(CURSOR)
                .param("routeId", routeId)
                .query((rs, row) -> Optional.ofNullable(instant(rs.getObject(1, OffsetDateTime.class))))
                .single();
    }

    @Override
    public Optional<Instant> newestUpdate(String routeId, DateRange serviceDates) {
        Optional<Instant> newest = Optional.empty();
        for (LocalDate date = serviceDates.from(); !date.isAfter(serviceDates.to()); date = date.plusDays(1)) {
            Optional<Instant> ofDate = jdbc.sql(NEWEST_UPDATE)
                    .param("serviceDate", date)
                    .param("routeId", routeId)
                    .query((rs, row) -> Optional.ofNullable(instant(rs.getObject(1, OffsetDateTime.class))))
                    .single();
            if (ofDate.isPresent() && (newest.isEmpty() || ofDate.get().isAfter(newest.get()))) {
                newest = ofDate;
            }
        }
        return newest;
    }

    @Override
    public Map<Integer, BaselineState> baselines(String routeId) {
        Map<Integer, BaselineState> states = new HashMap<>();
        jdbc.sql(BASELINES)
                .param("routeId", routeId)
                .query(JdbcDisruptionStore::baseline)
                .list()
                .forEach(row -> states.put(row.directionId(), row.state()));
        return states;
    }

    @Override
    public Optional<DisruptionEpisode> openEpisode(UUID id) {
        return jdbc.sql(OPEN_EPISODE)
                .param("id", id)
                .query(JdbcDisruptionStore::episode)
                .optional();
    }

    @Override
    public List<Arrival> arrivals(String routeId, Instant from, Instant to, DateRange serviceDates) {
        return jdbc.sql(ARRIVALS)
                .param("fromDate", serviceDates.from())
                .param("toDate", serviceDates.to())
                .param("routeId", routeId)
                .param("from", utc(from))
                .param("to", utc(to))
                .query((rs, row) -> new Arrival(
                        rs.getInt("direction_id"),
                        rs.getString("stop_id"),
                        requireInstant(rs, "observed_at"),
                        rs.getInt("delay_seconds")))
                .list();
    }

    @Override
    public boolean hasArrivals(String routeId, Instant from, Instant to, DateRange serviceDates) {
        return Boolean.TRUE.equals(jdbc.sql(HAS_ARRIVALS)
                .param("fromDate", serviceDates.from())
                .param("toDate", serviceDates.to())
                .param("routeId", routeId)
                .param("from", utc(from))
                .param("to", utc(to))
                .query(Boolean.class)
                .single());
    }

    @Override
    public void saveBaseline(String routeId, int directionId, BaselineState state) {
        jdbc.sql(SAVE_BASELINE)
                .param("routeId", routeId)
                .param("directionId", directionId, Types.SMALLINT)
                .param("mean", state.mean())
                .param("variance", state.variance())
                .param("bucketCount", state.bucketCount())
                .param("lastBucket", utc(state.lastBucket()))
                .param("consecutiveHigh", state.consecutiveHigh(), Types.SMALLINT)
                .param("consecutiveLow", state.consecutiveLow(), Types.SMALLINT)
                .param("openEpisodeId", state.openEpisodeId(), Types.OTHER)
                .update();
    }

    @Override
    public void saveSnapshot(Instant snapshotHour, String routeId, int directionId, BaselineState state) {
        jdbc.sql(SAVE_SNAPSHOT)
                .param("snapshotHour", utc(snapshotHour))
                .param("routeId", routeId)
                .param("directionId", directionId, Types.SMALLINT)
                .param("mean", state.mean())
                .param("variance", state.variance())
                .param("bucketCount", state.bucketCount())
                .param("lastBucket", utc(state.lastBucket()))
                .param("consecutiveHigh", state.consecutiveHigh(), Types.SMALLINT)
                .param("consecutiveLow", state.consecutiveLow(), Types.SMALLINT)
                .param("openEpisodeId", state.openEpisodeId(), Types.OTHER)
                .update();
    }

    @Override
    public void saveEpisode(DisruptionEpisode episode, UUID batchId) {
        Instant end = episode.end();
        CloseReason reason = episode.closeReason();
        jdbc.sql(SAVE_EPISODE)
                .param("id", episode.id())
                .param("routeId", episode.routeId())
                .param("directionId", episode.directionId(), Types.SMALLINT)
                .param("episodeStart", utc(episode.start()))
                .param("episodeEnd", end == null ? null : utc(end), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("status", episode.isOpen() ? "OPEN" : "CLOSED")
                .param("closeReason", reason == null ? null : reason.name(), Types.VARCHAR)
                .param("baselineMean", BigDecimal.valueOf(episode.baselineMean()))
                .param("baselineStddev", BigDecimal.valueOf(episode.baselineStddev()))
                .param("currentAvg", BigDecimal.valueOf(episode.currentAvg()))
                .param("currentZ", BigDecimal.valueOf(episode.currentZ()))
                .param("peakAvg", BigDecimal.valueOf(episode.peakAvg()))
                .param("peakZ", BigDecimal.valueOf(episode.peakZ()))
                .param("sampleCount", episode.sampleCount())
                .param("affectedStopIds", episode.affectedStopIds().toArray(String[]::new))
                .param("lastBucket", utc(episode.lastBucket()))
                .param("batchId", batchId)
                .update();
    }

    @Override
    public List<String> routesNeedingTick() {
        return jdbc.sql(ROUTES_NEEDING_TICK).query(String.class).list();
    }

    private record BaselineRow(int directionId, BaselineState state) {}

    private static BaselineRow baseline(ResultSet rs, int row) throws SQLException {
        return new BaselineRow(
                rs.getInt("direction_id"),
                new BaselineState(
                        rs.getDouble("ewma_mean"),
                        rs.getDouble("ewma_var"),
                        rs.getInt("bucket_count"),
                        requireInstant(rs, "last_bucket"),
                        rs.getInt("consecutive_high"),
                        rs.getInt("consecutive_low"),
                        rs.getObject("open_episode_id", UUID.class)));
    }

    private static DisruptionEpisode episode(ResultSet rs, int row) throws SQLException {
        String reason = rs.getString("close_reason");
        OffsetDateTime end = rs.getObject("episode_end", OffsetDateTime.class);
        return new DisruptionEpisode(
                rs.getObject("id", UUID.class),
                rs.getString("route_id"),
                rs.getInt("direction_id"),
                requireInstant(rs, "episode_start"),
                instant(end),
                reason == null ? null : CloseReason.valueOf(reason),
                rs.getBigDecimal("baseline_mean_seconds").doubleValue(),
                rs.getBigDecimal("baseline_stddev_seconds").doubleValue(),
                rs.getBigDecimal("current_avg_delay_seconds").doubleValue(),
                rs.getBigDecimal("current_z_score").doubleValue(),
                rs.getBigDecimal("peak_avg_delay_seconds").doubleValue(),
                rs.getBigDecimal("peak_z_score").doubleValue(),
                rs.getInt("sample_count"),
                stopIds(rs.getArray("affected_stop_ids")),
                requireInstant(rs, "last_bucket"));
    }

    private static List<String> stopIds(Array array) throws SQLException {
        Object[] values = (Object[]) array.getArray();
        return Arrays.stream(values).map(String.class::cast).toList();
    }

    private static Instant requireInstant(ResultSet rs, String column) throws SQLException {
        Instant value = instant(rs.getObject(column, OffsetDateTime.class));
        return Objects.requireNonNull(value, column + " is NULL");
    }

    private static @Nullable Instant instant(@Nullable OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
