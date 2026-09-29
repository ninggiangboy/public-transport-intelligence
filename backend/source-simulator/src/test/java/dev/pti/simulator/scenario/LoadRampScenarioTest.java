package dev.pti.simulator.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.simulator.emit.MessageFactory;
import dev.pti.simulator.emit.OutboundMessage;
import dev.pti.simulator.feed.Feeds;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** DOC-25 §7.8 and T-14 (load-ramp) on the mini feed. */
class LoadRampScenarioTest {

    private static final Instant START = Instant.parse("2026-09-29T21:20:00Z");

    @Test
    void eachStepEmitsTheMultipleOfTheBaseRate() {
        ScenarioKit plain = new ScenarioKit(Feeds.mini(), START).run(Duration.ofMinutes(3));
        ScenarioKit kit = new ScenarioKit(Feeds.mini(), START);
        kit.start("load-ramp", "{\"steps\": [1, 2, 5], \"stepDuration\": \"PT1M\", \"rampDown\": false}");
        kit.run(Duration.ofMinutes(3));

        double[] multipliers = {1, 2, 5};
        for (int step = 0; step < 3; step++) {
            double base = positions(plain.sent(), step);
            double ramped = positions(kit.sent(), step);
            assertThat(ramped / base).as("step %d", step).isBetween(multipliers[step] * 0.9, multipliers[step] * 1.1);
        }
    }

    @Test
    void rampsDownAndRestoresTheRateItFound() {
        ScenarioKit kit = new ScenarioKit(Feeds.mini(), START, 42, 1.5);
        ScenarioRun run =
                kit.start("load-ramp", "{\"steps\": [2, 4], \"stepDuration\": \"PT1M\", \"includeTicketing\": true}");
        assertThat(run.plannedEndAt()).isEqualTo(START.plus(Duration.ofMinutes(3)));
        assertThat(kit.engine.loadRampRunning()).isTrue();
        assertThat(kit.harness.rate().gtfsRt()).isEqualTo(2.0);
        assertThat(kit.harness.rate().ticketing()).isEqualTo(2.0);

        kit.run(Duration.ofSeconds(90));
        assertThat(kit.harness.rate().gtfsRt()).isEqualTo(4.0);
        kit.run(Duration.ofSeconds(60));
        assertThat(kit.harness.rate().gtfsRt()).as("back down").isEqualTo(2.0);
        kit.run(Duration.ofSeconds(31));

        assertThat(kit.runs.status(run.runId())).isEqualTo(RunStatus.COMPLETED);
        assertThat(kit.engine.loadRampRunning()).isFalse();
        assertThat(kit.harness.rate().gtfsRt()).isEqualTo(1.5);
        assertThat(kit.harness.rate().ticketing()).isEqualTo(1.0);
    }

    @Test
    void stoppingRestoresTheRateAtOnce() {
        ScenarioKit kit = new ScenarioKit(Feeds.mini(), START);
        ScenarioRun run = kit.start("load-ramp", "{\"steps\": [10]}");
        assertThat(kit.harness.rate().gtfsRt()).isEqualTo(10.0);

        kit.engine.stopRun(run.runId());

        assertThat(kit.harness.rate().gtfsRt()).isEqualTo(1.0);
        assertThat(kit.runs.status(run.runId())).isEqualTo(RunStatus.STOPPED);
    }

    @Test
    void reportsItsStep() {
        ScenarioKit kit = new ScenarioKit(Feeds.mini(), START);
        ScenarioRun run = kit.start("load-ramp", "{\"steps\": [1, 3], \"stepDuration\": \"PT1M\"}");
        kit.run(Duration.ofSeconds(61));

        assertThat(kit.engine.run(run.runId()).progress()).isEqualTo(Map.of("step", 2, "steps", 3, "multiplier", 3.0));
    }

    /** VehiclePositions whose event time falls in minute {@code step} of the run. */
    private static long positions(List<OutboundMessage> sent, int step) {
        Instant from = START.plus(Duration.ofMinutes(step)).plusSeconds(5);
        Instant to = START.plus(Duration.ofMinutes(step + 1));
        return sent.stream()
                .filter(m -> m.topic().equals(MessageFactory.VEHICLE_POSITIONS))
                .filter(m -> !m.ledger().eventTimestamp().isBefore(from)
                        && m.ledger().eventTimestamp().isBefore(to))
                .count();
    }
}
