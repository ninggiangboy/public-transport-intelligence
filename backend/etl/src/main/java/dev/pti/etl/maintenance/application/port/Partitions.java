package dev.pti.etl.maintenance.application.port;

import dev.pti.etl.maintenance.application.ManagedTable;
import java.time.LocalDate;

/** Creates and drops the partitions of the fact tables (DOC-14 §7.4, DOC-18 §5); both calls are idempotent. */
public interface Partitions {

    /** Creates the partitions of a table from yesterday to {@code today + daysAhead}; returns how many it created. */
    int ensure(ManagedTable table, LocalDate today);

    /** Drops the partitions of a table older than its retention; returns how many it dropped. */
    int dropBefore(ManagedTable table, LocalDate today);
}
