package dev.pti.etl.maintenance.application;

import java.time.Duration;

/** One partitioned fact table: how far ahead to create partitions and how long to keep them (DOC-18 §5). */
public record ManagedTable(String table, int daysAhead, Duration retention) {}
