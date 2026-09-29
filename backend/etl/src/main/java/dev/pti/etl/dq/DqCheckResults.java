package dev.pti.etl.dq;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@code ops.dq_check_result}: one row per rule run, violations or not (DOC-16 §4). */
public class DqCheckResults {

    /** The latest result of a rule, as the metrics show it. */
    public record Latest(long violations, @Nullable Long population, Instant checkedAt) {}

    private final JdbcTemplate jdbc;

    public DqCheckResults(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Instant> lastRun(PostWriteRule rule) {
        Timestamp last = jdbc.queryForObject(
                "SELECT max(checked_at) FROM ops.dq_check_result WHERE rule_id = ? AND scope = 'TABLE'",
                Timestamp.class,
                rule.id());
        return Optional.ofNullable(last).map(Timestamp::toInstant);
    }

    /** DQ-23 keeps its population next to the sample, so the relative threshold survives a restart. */
    public void insert(PostWriteRule rule, long violations, @Nullable Long population, String sample) {
        String stored = population == null ? sample : "{\"population\": " + population + ", \"rows\": " + sample + "}";
        jdbc.update("""
                INSERT INTO ops.dq_check_result (rule_id, scope, table_name, violation_count, sample)
                VALUES (?, 'TABLE', ?, ?, ?::jsonb)
                """, rule.id(), rule.table(), violations, stored);
    }

    public Map<String, Latest> latest() {
        Map<String, Latest> latest = new HashMap<>();
        jdbc.query("""
                SELECT DISTINCT ON (rule_id) rule_id, violation_count, (sample ->> 'population')::bigint AS population,
                       checked_at
                FROM ops.dq_check_result
                WHERE scope = 'TABLE'
                ORDER BY rule_id, checked_at DESC
                """, rs -> {
            long population = rs.getLong("population");
            latest.put(
                    rs.getString("rule_id"),
                    new Latest(
                            rs.getLong("violation_count"),
                            rs.wasNull() ? null : population,
                            rs.getTimestamp("checked_at").toInstant()));
        });
        return latest;
    }
}
