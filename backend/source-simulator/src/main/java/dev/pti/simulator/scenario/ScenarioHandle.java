package dev.pti.simulator.scenario;

import java.util.Map;

/** A running scenario (DOC-25 §7.1). */
public interface ScenarioHandle {

    /**
     * Stops acting: no new effect starts. Some effects wind down after this, e.g. delayed trips recovering or
     * pending refunds; their hooks detach themselves when done. Idempotent.
     */
    void stop();

    /** Free-form counters for {@code GET /sim/scenario-runs/{runId}} and {@code /sim/status}. */
    Map<String, Object> progress();

    /** Called about twice a second on the {@code sim-scenarios} thread, with real time in epoch milliseconds. */
    default void tick(long realMillis) {}
}
