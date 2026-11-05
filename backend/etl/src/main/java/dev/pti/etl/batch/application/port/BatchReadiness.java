package dev.pti.etl.batch.application.port;

/**
 * Whether the batch app has finished starting: the stale executions of a killed pod are recovered and the startup
 * tasks have run. Pollers wait for it before they touch the job catalog (DOC-19 §7.2).
 */
@FunctionalInterface
public interface BatchReadiness {

    boolean isReady();
}
