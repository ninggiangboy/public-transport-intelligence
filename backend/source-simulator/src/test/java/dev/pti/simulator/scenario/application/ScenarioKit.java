package dev.pti.simulator.scenario.application;

import dev.pti.simulator.control.adapter.in.web.ScenarioParamBinder;
import dev.pti.simulator.control.adapter.in.web.ScenarioParamsJson;
import dev.pti.simulator.emit.adapter.out.json.JacksonMessageRewriter;
import dev.pti.simulator.emit.adapter.out.metrics.MicrometerResendMetrics;
import dev.pti.simulator.emit.application.EmitterHarness;
import dev.pti.simulator.emit.application.ResendQueue;
import dev.pti.simulator.emit.domain.OutboundMessage;
import dev.pti.simulator.feed.domain.Feed;
import dev.pti.simulator.scenario.adapter.out.json.JacksonMessageCorruptor;
import dev.pti.simulator.scenario.adapter.out.metrics.MicrometerScenarioMetrics;
import dev.pti.simulator.scenario.application.port.MessageCorruptor;
import dev.pti.simulator.scenario.domain.Scenario;
import dev.pti.simulator.scenario.domain.ScenarioRun;
import dev.pti.simulator.ticketing.domain.SalePointCatalog;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The scenario engine wired like the application, on an {@link EmitterHarness} with a manual clock and an in-memory
 * run history. Scenarios that act on ticketing get their sale points from the same feed.
 */
final class ScenarioKit {

    static final JsonMapper MAPPER =
            JsonMapper.builder().addModule(ScenarioParamsJson.module()).build();

    static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    static final Duration MAX_DURATION = Duration.ofHours(2);

    final ScenarioHooks hooks = new ScenarioHooks();
    final EmitterHarness harness;
    final ResendQueue resends;
    final SalePointCatalog catalog;
    final InMemoryScenarioRuns runs = new InMemoryScenarioRuns();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final ScenarioParamBinder binder = new ScenarioParamBinder(MAPPER, VALIDATOR);
    final MessageCorruptor corruptor = new JacksonMessageCorruptor();
    final ScenarioEngine engine;

    ScenarioKit(Feed feed, Instant start) {
        this(feed, start, 42, 1.0);
    }

    ScenarioKit(Feed feed, Instant start, long seed, double gtfsRt) {
        harness = new EmitterHarness(feed, start, seed, gtfsRt, hooks, hooks, hooks);
        resends = new ResendQueue(
                harness.sink(),
                harness.clock(),
                200_000,
                new MicrometerResendMetrics(registry),
                new JacksonMessageRewriter());
        harness.afterTick(() -> resends.drainUntil(harness.clock().realNow().toEpochMilli()));
        catalog = SalePointCatalog.build(feed, LocalDate.of(2026, 9, 29), 60);
        List<Scenario<?>> scenarios = List.of(
                new BunchingScenario(feed, harness.emitter(), hooks),
                new DisruptionScenario(feed, hooks),
                new BadDataScenario(hooks, corruptor),
                new DuplicatesScenario(resends, harness.clock(), hooks),
                new TicketSpikeScenario(catalog, hooks),
                new RefundBurstScenario(catalog, hooks),
                new LoadRampScenario(harness.rate()));
        engine = new ScenarioEngine(
                scenarios, hooks, runs, harness.clock(), seed, MAX_DURATION, new MicrometerScenarioMetrics(registry));
        engine.start();
        harness.afterTick(engine::tick);
    }

    ScenarioRun start(String name, String json) {
        return start(name, json, "test");
    }

    ScenarioRun start(String name, @Nullable String json, @Nullable String requestedBy) {
        return start(engine.scenario(name), json == null ? null : json(json), requestedBy);
    }

    private <P extends Record> ScenarioRun start(
            Scenario<P> scenario, @Nullable JsonNode body, @Nullable String requestedBy) {
        ScenarioParamBinder.Bound<P> bound = binder.bind(scenario, body);
        return engine.start(scenario, bound.params(), bound.json(), requestedBy);
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
