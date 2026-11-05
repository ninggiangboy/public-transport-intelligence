package dev.pti.etl.batch.application.port;

/** Recovers the executions a killed pod left STARTED (DOC-19 §7.2, DR-24). */
public interface StaleExecutions {

    void recoverAll();
}
