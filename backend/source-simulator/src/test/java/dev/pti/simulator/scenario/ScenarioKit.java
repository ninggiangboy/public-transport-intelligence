package dev.pti.simulator.scenario;

import dev.pti.simulator.emit.EmitterHarness;
import dev.pti.simulator.emit.OutboundMessage;
import dev.pti.simulator.emit.ResendQueue;
import dev.pti.simulator.feed.Feed;
import dev.pti.simulator.ticketing.SalePointCatalog;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The scenario engine wired like the application, on an {@link EmitterHarness} with a manual clock and an in-memory
 * run history. Scenarios that act on ticketing get their sale points from the same feed.
 */
final class ScenarioKit {

    static final JsonMapper MAPPER = JsonMapper.builder().build();

    static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    static final Duration MAX_DURATION = Duration.ofHours(2);

    final ScenarioHooks hooks = new ScenarioHooks();
    final EmitterHarness harness;
    final ResendQueue resends;
    final SalePointCatalog catalog;
    final InMemoryScenarioRuns runs = new InMemoryScenarioRuns();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final ScenarioEngine engine;

    ScenarioKit(Feed feed, Instant start) {
        this(feed, start, 42, 1.0);
    }

    ScenarioKit(Feed feed, Instant start, long seed, double gtfsRt) {
        harness = new EmitterHarness(feed, start, seed, gtfsRt, hooks, hooks, hooks);
        resends = new ResendQueue(harness.sink(), harness.clock(), 200_000, registry);
        harness.afterTick(() -> resends.drainUntil(harness.clock().realNow().toEpochMilli()));
        catalog = SalePointCatalog.build(feed, LocalDate.of(2026, 9, 29), 60);
        List<Scenario<?>> scenarios = List.of(
                new BunchingScenario(feed, harness.emitter()),
                new DisruptionScenario(feed),
                new BadDataScenario(),
                new DuplicatesScenario(resends, harness.clock()),
                new TicketSpikeScenario(catalog),
                new RefundBurstScenario(catalog),
                new LoadRampScenario(harness.rate()));
        engine = new ScenarioEngine(
                scenarios,
                hooks,
                new ParamBinder(MAPPER, VALIDATOR),
                runs,
                harness.clock(),
                MAPPER,
                seed,
                MAX_DURATION,
                registry);
        engine.start();
        harness.afterTick(engine::tick);
    }

    ScenarioRun start(String name, String json) {
        return engine.start(name, json(json), "test");
    }

    /** Runs the emitter and the engine's tick for {@code duration} of business time. */
    ScenarioKit run(Duration duration) {
        harness.run(duration);
        return this;
    }

    List<OutboundMessage> sent() {
        return harness.sent();
    }

    static JsonNode json(String json) {
        return MAPPER.readTree(json);
    }
}
