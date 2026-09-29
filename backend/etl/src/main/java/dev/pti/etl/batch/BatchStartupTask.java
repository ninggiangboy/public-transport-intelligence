package dev.pti.etl.batch;

/**
 * Work {@code etl-batch} does once at startup, after the stale executions of a killed pod are recovered and before the
 * pollers start, e.g. loading the first GTFS feed (DOC-21 §2).
 */
@FunctionalInterface
public interface BatchStartupTask extends Runnable {}
