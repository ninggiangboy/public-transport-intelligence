package dev.pti.simulator.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.simulator.feed.Feeds;
import dev.pti.simulator.motion.SegmentOverlay;
import dev.pti.simulator.motion.TripRun;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The run life cycle of DOC-25 §7.1 and the request checks of §8. */
class ScenarioEngineTest {

    private static final Instant START = Instant.parse("2026-09-29T21:20:00Z");

    @Test
    void aRunCompletesAfterItsDuration() {
        ScenarioKit kit = new ScenarioKit(Feeds.mini(), START);
        ScenarioRun run = kit.start("bad-data", "{\"duration\": \"PT1M\"}");
        assertThat(run.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(run.plannedEndAt()).isEqualTo(START.plus(Duration.ofMinutes(1)));
        assertThat(kit.engine.running()).extracting(ScenarioRun::runId).containsExactly(run.runId());

        kit.run(Duration.ofSeconds(59));
        assertThat(kit.runs.status(run.runId())).isEqualTo(RunStatus.RUNNING);
        kit.run(Duration.ofSeconds(2));

        assertThat(kit.runs.status(run.runId())).isEqualTo(RunStatus.COMPLETED);
        assertThat(kit.engine.running()).isEmpty();
        assertThat(kit.hooks.attached(run.runId())).isFalse();
    }

    @Test
    void storesTheParamsWithTheirDefaultsAndWhoAsked() {
        ScenarioKit kit = new ScenarioKit(Feeds.mini(), START);
        ScenarioRun run = kit.engine.start(
                "bad-data", ScenarioKit.json("{\"ratio\": 0.01}"), "experiment:EXP-03/20261002T101500Z-r07");

        ScenarioRun stored = kit.runs.find(run.runId()).orElseThrow();
        assertThat(stored.params().get("ratio").asDouble()).isEqualTo(0.01);
        assertThat(stored.params().get("duration").asString()).isEqualTo("PT10M");
        assertThat(stored.params().get("kinds")).hasSize(InvalidKind.values().length);
        assertThat(stored.requestedBy()).isEqualTo("experiment:EXP-03/20261002T101500Z-r07");
        assertThat(kit.runs.experimentRunIds).containsEntry(run.runId(), "EXP-03/20261002T101500Z-r07");
        assertThat(kit.engine.start("duplicates", null, " ").requestedBy()).isEqualTo("cli");
    }

    @Test
    void stoppingIsIdempotentAndKeepsTheFirstEnd() {
        ScenarioKit kit = new ScenarioKit(Feeds.mini(), START);
        ScenarioRun run = kit.start("bad-data", "{}");

        kit.engine.stopRun(run.runId());
        kit.engine.stopRun(run.runId());
        kit.engine.stopScenario("bad-data");

        assertThat(kit.runs.status(run.runId())).isEqualTo(RunStatus.STOPPED);
        assertThatThrownBy(() -> kit.engine.stopRun(UUID.randomUUID()))
                .isInstanceOfSatisfying(
                        ScenarioException.class,
                        e -> assertThat(e.problem()).isEqualTo(ScenarioException.Problem.SCENARIO_RUN_NOT_FOUND));
    }

    @Test
    void stopScenarioStopsEveryRunOfThatScenarioOnly() {
        ScenarioKit kit = new ScenarioKit(Feeds.mini(), START);
        ScenarioRun a = kit.start("ticket-spike", "{\"salePointId\": \"KIOSK-001\"}");
        ScenarioRun b = kit.start("ticket-spike", "{\"salePointId\": \"KIOSK-002\"}");
        ScenarioRun other = kit.start("bad-data", "{}");

        kit.engine.stopScenario("ticket-spike");

        assertThat(kit.runs.status(a.runId())).isEqualTo(RunStatus.STOPPED);
        assertThat(kit.runs.status(b.runId())).isEqualTo(RunStatus.STOPPED);
        assertThat(kit.runs.status(other.runId())).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void aSingleScenarioRunsOnceAtATime() {
        ScenarioKit kit = new ScenarioKit(Feeds.mini(), START);
        ScenarioRun first = kit.start("duplicates", "{}");

        assertThatThrownBy(() -> kit.start("duplicates", "{}")).isInstanceOfSatisfying(ScenarioException.class, e -> {
            assertThat(e.problem()).isEqualTo(ScenarioException.Problem.SCENARIO_CONFLICT);
            assertThat(e.getMessage()).contains(first.runId().toString());
        });
        kit.engine.stopRun(first.runId());
        assertThat(kit.start("duplicates", "{}").status()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void aHookThatThrowsFailsItsRunAndTheSimulatorCarriesOn() {
        ScenarioKit kit = new ScenarioKit(Feeds.mini(), START);
        ScenarioRun run = kit.start("disruption", "{\"routeId\": \"18\"}");
        kit.hooks.addOverlay(run.runId(), new SegmentOverlay() {
            @Override
            public double segmentDelta(TripRun trip, int fromIndex, long departureMillis, double departureDelay) {
                throw new IllegalStateException("boom");
            }
        });

        kit.run(Duration.ofMinutes(2));

        assertThat(kit.runs.status(run.runId())).isEqualTo(RunStatus.FAILED);
        assertThat(kit.hooks.attached(run.runId())).isFalse();
        int sent = kit.sent().size();
        kit.run(Duration.ofMinutes(1));
        assertThat(kit.sent().size()).isGreaterThan(sent);
    }

    @Test
    void reportsEveryInvalidFieldAndRefusesUnknownOnes() {
        ScenarioKit kit = new ScenarioKit(Feeds.mini(), START);

        assertThatThrownBy(() -> kit.start("bunching", "{\"pairs\": 5, \"targetGapRatio\": 0.9, \"speed\": 3}"))
                .isInstanceOfSatisfying(
                        ScenarioException.class,
                        e -> assertThat(e.errors())
                                .containsExactly(new ScenarioException.FieldError("speed", "unknown parameter")));
        assertThatThrownBy(() -> kit.start("bunching", "{\"pairs\": 5, \"targetGapRatio\": 0.9}"))
                .isInstanceOfSatisfying(
                        ScenarioException.class,
                        e -> assertThat(e.errors())
                                .extracting(ScenarioException.FieldError::field)
                                .containsExactly("pairs", "routeId", "targetGapRatio"));
        assertThatThrownBy(() -> kit.start("bad-data", "{\"duration\": \"ten minutes\"}"))
                .isInstanceOfSatisfying(
                        ScenarioException.class,
                        e -> assertThat(e.errors())
                                .extracting(ScenarioException.FieldError::field)
                                .containsExactly("duration"));
        assertThatThrownBy(() -> kit.start("bad-data", "[1, 2]"))
                .isInstanceOfSatisfying(
                        ScenarioException.class,
                        e -> assertThat(e.problem()).isEqualTo(ScenarioException.Problem.INVALID_PARAM));
    }

    @Test
    void refusesRunsLongerThanTheMaximum() {
        ScenarioKit kit = new ScenarioKit(Feeds.mini(), START);

        assertThatThrownBy(() -> kit.start("bad-data", "{\"duration\": \"PT3H\"}"))
                .isInstanceOfSatisfying(
                        ScenarioException.class,
                        e -> assertThat(e.errors())
                                .extracting(ScenarioException.FieldError::field)
                                .containsExactly("duration"));
        assertThatThrownBy(() -> kit.start("load-ramp", "{\"steps\": [1, 2, 3], \"stepDuration\": \"PT30M\"}"))
                .isInstanceOfSatisfying(
                        ScenarioException.class,
                        e -> assertThat(e.errors())
                                .extracting(ScenarioException.FieldError::field)
                                .containsExactly("stepDuration"));
    }

    @Test
    void anUnknownScenarioIsNotFound() {
        ScenarioKit kit = new ScenarioKit(Feeds.mini(), START);

        assertThatThrownBy(() -> kit.start("earthquake", "{}"))
                .isInstanceOfSatisfying(
                        ScenarioException.class,
                        e -> assertThat(e.problem()).isEqualTo(ScenarioException.Problem.UNKNOWN_SCENARIO));
        assertThatThrownBy(() -> kit.engine.stopScenario("earthquake")).isInstanceOf(ScenarioException.class);
    }

    @Test
    void closesRunsLeftRunningAtStartup() {
        ScenarioKit previous = new ScenarioKit(Feeds.mini(), START);
        ScenarioRun left = previous.start("bad-data", "{}");
        ScenarioEngine restarted = new ScenarioEngine(
                List.of(),
                new ScenarioHooks(),
                new ParamBinder(ScenarioKit.MAPPER, ScenarioKit.VALIDATOR),
                previous.runs,
                previous.harness.clock(),
                ScenarioKit.MAPPER,
                42,
                ScenarioKit.MAX_DURATION,
                new SimpleMeterRegistry());

        restarted.start();

        assertThat(previous.runs.status(left.runId())).isEqualTo(RunStatus.FAILED);
        assertThat(restarted.running()).isEmpty();
    }

    @Test
    void extractsTheExperimentRunId() {
        assertThat(ScenarioEngine.experimentRunId("experiment:EXP-01/r1")).isEqualTo("EXP-01/r1");
        assertThat(ScenarioEngine.experimentRunId("experiment:")).isNull();
        assertThat(ScenarioEngine.experimentRunId("user:alice")).isNull();
    }
}
