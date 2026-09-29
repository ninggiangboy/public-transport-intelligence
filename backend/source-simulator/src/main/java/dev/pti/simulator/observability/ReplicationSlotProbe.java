package dev.pti.simulator.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code pti_source_replication_slot_retained_bytes{slot}} (DR-71, DOC-28 §3.2): the WAL that each replication slot
 * on {@code pg-source} keeps, read as {@code source_simulator}. When the query fails the gauges keep their last
 * value and {@code pti_sim_slot_probe_errors_total} goes up. Runs on its own tick loop.
 */
public final class ReplicationSlotProbe {

    private static final Logger log = LoggerFactory.getLogger(ReplicationSlotProbe.class);

    static final String QUERY = """
            SELECT slot_name, coalesce(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn), 0)::bigint AS retained
            FROM pg_replication_slots
            """;

    private final JdbcTemplate jdbc;
    private final MultiGauge retained;
    private final Counter errors;

    public ReplicationSlotProbe(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.retained = MultiGauge.builder("pti.source.replication.slot.retained")
                .baseUnit("bytes")
                .register(registry);
        this.errors = Counter.builder("pti.sim.slot.probe.errors").register(registry);
    }

    public void probe() {
        try {
            List<MultiGauge.Row<?>> rows = new ArrayList<>();
            jdbc.query(QUERY, rs -> {
                rows.add(MultiGauge.Row.of(Tags.of("slot", rs.getString("slot_name")), rs.getLong("retained")));
            });
            retained.register(rows, true);
        } catch (DataAccessException e) {
            errors.increment();
            log.warn("Replication slot probe failed: {}", e.toString());
        }
    }
}
