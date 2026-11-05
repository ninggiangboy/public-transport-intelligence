package dev.pti.simulator.scenario.application.port;

/** The running-run gauge (DOC-28 §3.1). */
public interface ScenarioMetrics {

    /** {@code pti_sim_scenario_active{scenario}}: how many runs of a scenario are in progress. */
    void active(String scenario, int count);
}
