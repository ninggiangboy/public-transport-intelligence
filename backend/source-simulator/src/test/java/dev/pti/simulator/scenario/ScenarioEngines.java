package dev.pti.simulator.scenario;

import dev.pti.simulator.emit.EmitterHarness;
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
                new ParamBinder(ScenarioKit.MAPPER, ScenarioKit.VALIDATOR),
                new InMemoryScenarioRuns(),
                harness.clock(),
                ScenarioKit.MAPPER,
                42,
                ScenarioKit.MAX_DURATION,
                new SimpleMeterRegistry());
    }
}
