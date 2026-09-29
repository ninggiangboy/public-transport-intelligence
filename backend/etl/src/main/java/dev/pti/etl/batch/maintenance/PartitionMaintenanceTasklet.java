package dev.pti.etl.batch.maintenance;

import dev.pti.common.time.BusinessClock;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Creates the partitions of the coming days and drops those past retention (DOC-18 §5, DOC-14 §7.4). Dates are
 * business dates of the agency, so the job follows {@code PTI_CLOCK_OFFSET} (test L-01). Both functions are
 * idempotent, so a restart simply runs again.
 */
public class PartitionMaintenanceTasklet implements Tasklet {

    /** One partitioned fact table: how far ahead to create partitions and how long to keep them. */
    public record ManagedTable(String table, int daysAhead, Duration retention) {}

    private final JdbcTemplate jdbc;
    private final BusinessClock clock;
    private final ZoneId agencyZone;
    private final List<ManagedTable> tables;
    private final RetentionMetrics metrics;

    public PartitionMaintenanceTasklet(
            JdbcTemplate jdbc,
            BusinessClock clock,
            ZoneId agencyZone,
            List<ManagedTable> tables,
            MeterRegistry meters) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.agencyZone = agencyZone;
        this.tables = List.copyOf(tables);
        this.metrics = new RetentionMetrics(meters);
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), agencyZone);
        List<String> summary = new ArrayList<>();
        for (ManagedTable t : tables) {
            Integer created = jdbc.queryForObject(
                    "SELECT dw.ensure_partitions(?, ?, ?)",
                    Integer.class,
                    t.table(),
                    today.minusDays(1),
                    today.plusDays(t.daysAhead()));
            Integer dropped = jdbc.queryForObject(
                    "SELECT dw.drop_partitions_before(?, ?)",
                    Integer.class,
                    t.table(),
                    today.minusDays(t.retention().toDays()));
            int c = created == null ? 0 : created;
            int d = dropped == null ? 0 : dropped;
            metrics.deleted(t.table(), d);
            summary.add(t.table() + ": created " + c + ", dropped " + d);
        }
        contribution.setExitStatus(
                ExitStatus.COMPLETED.addExitDescription("Business date " + today + "; " + String.join("; ", summary)));
        return RepeatStatus.FINISHED;
    }
}
