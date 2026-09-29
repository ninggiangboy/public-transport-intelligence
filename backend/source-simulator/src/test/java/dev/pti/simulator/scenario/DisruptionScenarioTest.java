package dev.pti.simulator.scenario;

import static dev.pti.simulator.scenario.BunchingScenarioTest.envelope;
import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.common.message.Envelope;
import dev.pti.common.message.ScheduleRelationship;
import dev.pti.common.message.StopTimeUpdate;
import dev.pti.common.message.TripUpdate;
import dev.pti.simulator.emit.MessageFactory;
import dev.pti.simulator.emit.OutboundMessage;
import dev.pti.simulator.feed.Feeds;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** DOC-25 §7.3 and T-14 (disruption) on the mini feed, route 18, from 16:20 CDT on a Tuesday. */
class DisruptionScenarioTest {

    private static final Instant START = Instant.parse("2026-09-29T21:20:00Z");

    @Test
    void raisesTheObservedDelayOnTheRouteByAtLeastFourMinutes() {
        ScenarioKit plain = new ScenarioKit(Feeds.mini(), START).run(Duration.ofMinutes(20));
        ScenarioKit disrupted = new ScenarioKit(Feeds.mini(), START);
        disrupted.start("disruption", "{\"routeId\": \"18\", \"duration\": \"PT30M\"}");
        disrupted.run(Duration.ofMinutes(20));

        double before = meanObservedDelay(plain.sent(), "18", START.plus(Duration.ofMinutes(10)));
        double after = meanObservedDelay(disrupted.sent(), "18", START.plus(Duration.ofMinutes(10)));

        assertThat(after - before).as("mean delay %s s → %s s", before, after).isGreaterThanOrEqualTo(240);
    }

    @Test
    void leavesOtherRoutesAlone() {
        ScenarioKit plain = new ScenarioKit(Feeds.mini(), START).run(Duration.ofMinutes(10));
        ScenarioKit disrupted = new ScenarioKit(Feeds.mini(), START);
        disrupted.start("disruption", "{\"routeId\": \"18\", \"duration\": \"PT30M\"}");
        disrupted.run(Duration.ofMinutes(10));

        assertThat(meanObservedDelay(disrupted.sent(), "901", START))
                .isEqualTo(meanObservedDelay(plain.sent(), "901", START));
    }

    @Test
    void tripsRecoverAfterTheRunAndTheOverlayDetaches() {
        ScenarioKit kit = new ScenarioKit(Feeds.mini(), START);
        ScenarioRun run = kit.start("disruption", "{\"routeId\": \"18\", \"duration\": \"PT10M\"}");
        kit.run(Duration.ofMinutes(10).plusSeconds(1));
        assertThat(kit.runs.status(run.runId())).isEqualTo(RunStatus.COMPLETED);
        assertThat(kit.hooks.attached(run.runId())).as("recovering").isTrue();

        kit.run(Duration.ofMinutes(60));

        assertThat(kit.hooks.attached(run.runId())).isFalse();
    }

    @Test
    void skipsStopsOfAffectedTripsWhenAsked() {
        ScenarioKit kit = new ScenarioKit(Feeds.mini(), START);
        kit.start("disruption", "{\"routeId\": \"18\", \"skipStops\": true, \"duration\": \"PT30M\"}");
        kit.run(Duration.ofMinutes(10));

        long skipped = tripUpdates(kit.sent(), "18").stream()
                .flatMap(tu -> tu.stopTimeUpdates().stream())
                .filter(u -> u.scheduleRelationship() == ScheduleRelationship.SKIPPED)
                .peek(u -> assertThat(u.arrival()).isNull())
                .count();
        assertThat(skipped).isPositive();
    }

    @Test
    void marksOnlyTheRoutesMessages() {
        ScenarioKit kit = new ScenarioKit(Feeds.mini(), START);
        ScenarioRun run = kit.start("disruption", "{\"routeId\": \"18\", \"directionId\": 1, \"duration\": \"PT30M\"}");
        kit.run(Duration.ofMinutes(5));

        for (OutboundMessage m : kit.sent()) {
            String json = m.value();
            boolean affected = json.contains("\"route_id\":\"18\"") && json.contains("\"direction_id\":1");
            assertThat(m.ledger().scenarioRunId()).as(json).isEqualTo(affected ? run.runId() : null);
        }
    }

    private static List<TripUpdate> tripUpdates(List<OutboundMessage> sent, String routeId) {
        return sent.stream()
                .filter(m -> m.topic().equals(MessageFactory.TRIP_UPDATES))
                .map(m -> (TripUpdate) envelope(m).payload())
                .filter(tu -> tu.routeId().equals(routeId))
                .toList();
    }

    /** Mean delay of the arrivals reported as observed after {@code from}. */
    private static double meanObservedDelay(List<OutboundMessage> sent, String routeId, Instant from) {
        long sum = 0;
        long n = 0;
        for (OutboundMessage m : sent) {
            if (!m.topic().equals(MessageFactory.TRIP_UPDATES)) {
                continue;
            }
            Envelope<?> e = envelope(m);
            TripUpdate tu = (TripUpdate) e.payload();
            if (!tu.routeId().equals(routeId)) {
                continue;
            }
            for (StopTimeUpdate u : tu.stopTimeUpdates()) {
                if (u.arrival() != null
                        && u.arrival().time().isAfter(from)
                        && !u.arrival().time().isAfter(e.eventTimestamp())) {
                    sum += u.arrival().delay();
                    n++;
                }
            }
        }
        assertThat(n).isPositive();
        return sum / (double) n;
    }
}
