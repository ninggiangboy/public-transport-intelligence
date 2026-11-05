package dev.pti.simulator.scenario.adapter.in.scheduling;

import dev.pti.simulator.scenario.application.ScenarioEngine;
import org.springframework.context.SmartLifecycle;

/**
 * Runs the engine's startup step before the tick loops start (DOC-25 §7.1): rows a previous process left
 * {@code RUNNING} are marked {@code FAILED} before anything new can start.
 */
public final class ScenarioEngineLifecycle implements SmartLifecycle {

    /** Before the tick loops, so old runs are closed before anything new can start. */
    public static final int PHASE = 500;

    private final ScenarioEngine engine;
    private volatile boolean running;

    public ScenarioEngineLifecycle(ScenarioEngine engine) {
        this.engine = engine;
    }

    @Override
    public void start() {
        engine.start();
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
