package dev.pti.analytics.retention.adapter.out.jdbc;

import dev.pti.analytics.retention.application.port.InsightRetentionStore;
import dev.pti.analytics.retention.domain.RetentionCutoff;
import dev.pti.analytics.retention.domain.RetentionTarget;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link InsightRetentionStore} with the conditions of DOC-23 §12.3. Each statement deletes by {@code ctid} in a
 * subselect with {@code LIMIT}, as the other retention steps do (DOC-18 §5). The statements run in the transaction of
 * the caller; this adapter opens none (DOC-49 §5.1).
 */
public class JdbcInsightRetentionStore implements InsightRetentionStore {

    private final JdbcClient jdbc;

    public JdbcInsightRetentionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int deleteExpired(RetentionTarget target, RetentionCutoff cutoff, int limit) {
        Object bound = target.basis() == RetentionTarget.Basis.SERVICE_DATE
                ? cutoff.serviceDate()
                : OffsetDateTime.ofInstant(cutoff.instant(), ZoneOffset.UTC);
        return jdbc.sql(sql(target))
                .param("cutoff", bound)
                .param("limit", limit)
                .update();
    }

    private static String sql(RetentionTarget target) {
        return switch (target) {
            // An open episode is kept however old it is.
            case BUNCHING, DISRUPTION -> purge(target, "status = 'CLOSED' AND episode_end < :cutoff");
            case TICKETING_ANOMALY -> purge(target, "detected_at < :cutoff");
            case OTP_SCORECARD -> purge(target, "service_date < :cutoff");
            case DISPATCH_SUGGESTION -> purge(target, "created_at < :cutoff");
            case BASELINE_SNAPSHOT -> purge(target, "snapshot_hour < :cutoff");
            // A cursor is kept while its route still has a pair state: the tick cleans those up (DOC-23 §5).
            case BUNCHING_CURSOR -> purge(target, """
                    last_tick < :cutoff
                      AND NOT EXISTS (SELECT 1 FROM insight.analytics_bunching_pair_state p
                                      WHERE p.route_id = analytics_bunching_cursor.route_id)""");
        };
    }

    private static String purge(RetentionTarget target, String condition) {
        String table = target.table();
        return "DELETE FROM " + table + " WHERE ctid IN (SELECT ctid FROM " + table + " WHERE " + condition
                + " LIMIT :limit)";
    }
}
