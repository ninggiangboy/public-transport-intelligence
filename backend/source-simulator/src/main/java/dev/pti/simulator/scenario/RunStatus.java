package dev.pti.simulator.scenario;

/** The status of a row of {@code sim.sim_scenario_run} (DOC-13 §6.3, DOC-25 §7.1). */
public enum RunStatus {
    RUNNING,
    /** Ran for its whole duration. */
    COMPLETED,
    /** Stopped through the API. */
    STOPPED,
    /** Threw while running, or was still running when the simulator stopped. */
    FAILED
}
