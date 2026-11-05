package dev.pti.etl.maintenance.application;

import java.time.Duration;

/**
 * One table to purge (DOC-18 §5): every delete removes rows that {@code condition} matches before the cutoff, at
 * most {@code batchSize} of them per transaction.
 *
 * @param condition a {@code WHERE} fragment with one {@code ?}, the cutoff; it must match only expired rows
 */
public record PurgeTarget(String table, String condition, Duration retention) {}
