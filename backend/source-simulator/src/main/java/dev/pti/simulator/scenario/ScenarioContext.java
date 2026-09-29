package dev.pti.simulator.scenario;

import java.util.UUID;

/**
 * What a scenario gets when it starts (DOC-25 §7.1).
 *
 * @param runId the run's id: every message it changes or adds carries it in the ledger
 * @param seed the simulator seed, for deterministic choices
 * @param realStartMillis real time of the start, epoch milliseconds
 */
public record ScenarioContext(UUID runId, ScenarioHooks hooks, long seed, long realStartMillis) {}
