package dev.pti.simulator.scenario;

/** Whether runs of one scenario may overlap (DOC-25 §7.1, §8). */
public enum Concurrency {
    /** Runs may overlap when they target different routes or sale points. */
    PER_TARGET,
    /** One run at a time. */
    SINGLE
}
