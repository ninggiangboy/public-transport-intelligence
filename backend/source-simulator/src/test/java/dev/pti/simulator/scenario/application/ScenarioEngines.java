package dev.pti.simulator.scenario.application;

import dev.pti.simulator.emit.application.EmitterHarness;
import dev.pti.simulator.scenario.adapter.out.metrics.MicrometerScenarioMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;

/** Engines for tests outside this package. */
public final class ScenarioEngines {

    private ScenarioEngines() {}

    /** An engine with no scenarios: nothing runs, nothing is running. */
    public static ScenarioEngine idle(EmitterHarness harness) {
        return new ScenarioEngine(
                List.of(),
                new ScenarioHooks(),
                new InMemoryScenarioRuns(),
                harness.clock(),
                42,
                ScenarioKit.MAX_DURATION,
                new MicrometerScenarioMetrics(new SimpleMeterRegistry()));
    }
}
