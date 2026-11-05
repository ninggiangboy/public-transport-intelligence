package dev.pti.etl.maintenance.adapter.out.jdbc;

import dev.pti.etl.maintenance.application.ManagedTable;
import dev.pti.etl.maintenance.application.port.Partitions;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@link Partitions} on the {@code dw} partition helpers of DOC-14 §7.4; both calls are idempotent. */
public final class JdbcPartitions implements Partitions {

    private final JdbcTemplate jdbc;

    public JdbcPartitions(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int ensure(ManagedTable table, LocalDate today) {
        Integer created = jdbc.queryForObject(
                "SELECT dw.ensure_partitions(?, ?, ?)",
                Integer.class,
                table.table(),
                today.minusDays(1),
                today.plusDays(table.daysAhead()));
        return created == null ? 0 : created;
    }

    @Override
    public int dropBefore(ManagedTable table, LocalDate today) {
        Integer dropped = jdbc.queryForObject(
                "SELECT dw.drop_partitions_before(?, ?)",
                Integer.class,
                table.table(),
                today.minusDays(table.retention().toDays()));
        return dropped == null ? 0 : dropped;
    }
}
