package dev.pti.simulator.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.common.json.MessageJson;
import dev.pti.common.message.Envelope;
import dev.pti.common.message.StopTimeUpdate;
import dev.pti.common.message.TripUpdate;
import dev.pti.common.message.VehiclePosition;
import dev.pti.simulator.emit.MessageFactory;
import dev.pti.simulator.emit.OutboundMessage;
import dev.pti.simulator.feed.Feeds;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** DOC-25 §7.2 and T-14 (bunching) on the mini feed, route 18, at 16:28 CDT on a Tuesday. */
class BunchingScenarioTest {

    private static final Instant START = Instant.parse("2026-09-29T21:28:00Z");

    @Test
    void theFollowerClosesUpToTheTargetGapWithinFifteenMinutes() {
        ScenarioKit kit = new ScenarioKit(Feeds.mini(), START).run(Duration.ofMinutes(1));
        ScenarioRun run = kit.start("bunching", "{\"routeId\": \"18\", \"directionId\": 0, \"duration\": \"PT20M\"}");
        Instant started = kit.harness.clock().instant();
        Map<String, Object> pair = firstPair(run);
        kit.run(Duration.ofMinutes(15));

        String leaderTrip = tripOf(kit.sent(), (String) pair.get("leaderVehicleId"));
        String followerTrip = tripOf(kit.sent(), (String) pair.get("followerVehicleId"));
        Map<String, Instant> leader = observedArrivals(kit.sent(), leaderTrip);
        Map<String, Instant> follower = observedArrivals(kit.sent(), followerTrip);
        long target = ((Number) pair.get("targetGapSeconds")).longValue();

        long closest = Long.MAX_VALUE;
        for (Map.Entry<String, Instant> stop : follower.entrySet()) {
            Instant l = leader.get(stop.getKey());
            if (l != null && stop.getValue().isAfter(started)) {
                closest = Math.min(closest, Duration.between(l, stop.getValue()).toSeconds());
            }
        }
        assertThat(closest)
                .as("follower %s behind leader %s, headway %s s", followerTrip, leaderTrip, pair.get("headwaySeconds"))
                .isLessThanOrEqualTo(target);
    }

    @Test
    void withoutTheScenarioThePairKeepsItsHeadway() {
        ScenarioKit kit = new ScenarioKit(Feeds.mini(), START).run(Duration.ofMinutes(1));
        List<BunchingScenario.Pair> pairs = kit.harness
                .emitter()
                .inspect((vehicles, t) -> BunchingScenario.pick(
                        vehicles, t, new BunchingScenario.Params("18", 0, 1, 0.2, Duration.ofMinutes(20))));
        assertThat(pairs).hasSize(1);
        BunchingScenario.Pair pair = pairs.getFirst();
        Instant started = kit.harness.clock().instant();
        kit.run(Duration.ofMinutes(15));

        Map<String, Instant> leader =
                observedArrivals(kit.sent(), pair.leader.run().schedule().tripId());
        Map<String, Instant> follower =
                observedArrivals(kit.sent(), pair.follower.run().schedule().tripId());
        long closest = Long.MAX_VALUE;
        for (Map.Entry<String, Instant> stop : follower.entrySet()) {
            Instant l = leader.get(stop.getKey());
            if (l != null && stop.getValue().isAfter(started)) {
                closest = Math.min(closest, Duration.between(l, stop.getValue()).toSeconds());
            }
        }
        assertThat(closest).isGreaterThan(Math.round(pair.targetSeconds));
    }

    @Test
    void marksTheMessagesOfBothVehiclesWithTheRun() {
        ScenarioKit kit = new ScenarioKit(Feeds.mini(), START).run(Duration.ofMinutes(1));
        ScenarioRun run = kit.start("bunching", "{\"routeId\": \"18\"}");
        Map<String, Object> pair = firstPair(run);
        int before = kit.sent().size();
        kit.run(Duration.ofMinutes(2));

        List<OutboundMessage> after = kit.sent().subList(before, kit.sent().size());
        for (OutboundMessage m : after) {
            if (m.topic().equals(MessageFactory.VEHICLE_POSITIONS)) {
                String vehicle = ((VehiclePosition) envelope(m).payload()).vehicleId();
                boolean paired =
                        vehicle.equals(pair.get("leaderVehicleId")) || vehicle.equals(pair.get("followerVehicleId"));
                UUID expected = paired ? run.runId() : null;
                assertThat(m.ledger().scenarioRunId()).as(vehicle).isEqualTo(expected);
            }
        }
    }

    @Test
    void refusesARailRouteAndAnUnknownRoute() {
        ScenarioKit kit = new ScenarioKit(Feeds.mini(), START);

        assertThatThrownBy(() -> kit.start("bunching", "{\"routeId\": \"901\"}"))
                .isInstanceOfSatisfying(
                        ScenarioException.class,
                        e -> assertThat(e.errors())
                                .containsExactly(new ScenarioException.FieldError("routeId", "must be a bus route")));
        assertThatThrownBy(() -> kit.start("bunching", "{\"routeId\": \"nope\"}"))
                .isInstanceOfSatisfying(
                        ScenarioException.class,
                        e -> assertThat(e.errors())
                                .containsExactly(new ScenarioException.FieldError("routeId", "unknown route")));
    }

    /** 03:00 CDT: no bus is on the road. */
    @Test
    void answersNoEligibleVehiclesWhenNoPairExists() {
        ScenarioKit kit =
                new ScenarioKit(Feeds.mini(), Instant.parse("2026-09-30T08:00:00Z")).run(Duration.ofSeconds(1));

        assertThatThrownBy(() -> kit.start("bunching", "{\"routeId\": \"18\"}"))
                .isInstanceOfSatisfying(
                        ScenarioException.class,
                        e -> assertThat(e.problem()).isEqualTo(ScenarioException.Problem.NO_ELIGIBLE_VEHICLES));
        assertThat(kit.runs.rows).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstPair(ScenarioRun run) {
        return ((List<Map<String, Object>>) run.progress().get("pairs")).getFirst();
    }

    private static String tripOf(List<OutboundMessage> sent, String vehicleId) {
        for (OutboundMessage m : sent.reversed()) {
            if (m.topic().equals(MessageFactory.VEHICLE_POSITIONS)
                    && envelope(m).payload() instanceof VehiclePosition vp
                    && vp.vehicleId().equals(vehicleId)) {
                return vp.tripId();
            }
        }
        throw new AssertionError("No position of " + vehicleId);
    }

    /** Observed arrival per stop of a trip: stops reported with an arrival no later than the message. */
    static Map<String, Instant> observedArrivals(List<OutboundMessage> sent, String tripId) {
        Map<String, Instant> arrivals = new HashMap<>();
        for (OutboundMessage m : sent) {
            if (!m.topic().equals(MessageFactory.TRIP_UPDATES)) {
                continue;
            }
            Envelope<?> e = envelope(m);
            TripUpdate tu = (TripUpdate) e.payload();
            if (!tu.tripId().equals(tripId)) {
                continue;
            }
            for (StopTimeUpdate u : tu.stopTimeUpdates()) {
                if (u.arrival() != null && !u.arrival().time().isAfter(e.eventTimestamp())) {
                    arrivals.putIfAbsent(u.stopId(), u.arrival().time());
                }
            }
        }
        return arrivals;
    }

    static Envelope<?> envelope(OutboundMessage m) {
        return MessageJson.toEnvelope(MessageJson.mapper().readTree(m.value()));
    }
}
