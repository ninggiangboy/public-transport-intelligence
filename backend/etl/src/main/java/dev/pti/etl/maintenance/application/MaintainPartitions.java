package dev.pti.etl.maintenance.application;

import dev.pti.etl.maintenance.application.port.Partitions;
import dev.pti.etl.maintenance.application.port.RetentionMetrics;
import java.time.LocalDate;

/** Creates the partitions of the coming days and drops those past retention of one fact table (DOC-18 §5). */
public final class MaintainPartitions {

    /** What one call did to one table; both functions are idempotent, so a restart repeats them safely. */
    public record Result(int created, int dropped) {}

    private final Partitions partitions;
    private final RetentionMetrics metrics;

    public MaintainPartitions(Partitions partitions, RetentionMetrics metrics) {
        this.partitions = partitions;
        this.metrics = metrics;
    }

    public Result maintain(ManagedTable table, LocalDate today) {
        int created = partitions.ensure(table, today);
        int dropped = partitions.dropBefore(table, today);
        metrics.deleted(table.table(), dropped);
        return new Result(created, dropped);
    }
}
