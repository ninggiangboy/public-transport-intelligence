package dev.pti.simulator.emit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.common.json.MessageJson;
import dev.pti.common.message.EntityType;
import dev.pti.common.message.Envelope;
import dev.pti.common.message.StopTimeUpdate;
import dev.pti.common.message.TripUpdate;
import dev.pti.common.message.VehiclePosition;
import dev.pti.simulator.feed.Feeds;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The emission schedule of DOC-25 §6.1 on the mini feed, at 16:30 on a Tuesday in Chicago. */
class EmitterTest {

    private static final Instant PEAK = Instant.parse("2026-09-29T21:30:00Z");

    private static EmitterHarness run(long seed, double rate) {
        return new EmitterHarness(Feeds.mini(), PEAK, seed, rate).run(Duration.ofMinutes(10));
    }

    @Test
    void eachVehicleReportsItsPositionEveryFiveSecondsOnItsOwnPhase() {
        List<OutboundMessage> sent = run(42, 1.0).sent();

        Map<String, List<Long>> byVehicle = new HashMap<>();
        for (OutboundMessage m : sent) {
            if (m.topic().equals(MessageFactory.VEHICLE_POSITIONS)) {
                VehiclePosition vp = (VehiclePosition) envelope(m).payload();
                byVehicle
                        .computeIfAbsent(vp.vehicleId(), k -> new ArrayList<>())
                        .add(envelope(m).eventTimestamp().toEpochMilli());
            }
        }

        assertThat(byVehicle).hasSizeGreaterThan(10);
        Set<Long> phases = new HashSet<>();
        byVehicle.forEach((vehicle, times) -> {
            for (int i = 1; i < times.size(); i++) {
                assertThat(times.get(i) - times.get(i - 1)).as(vehicle).isEqualTo(5_000);
            }
            phases.add(Math.floorMod(times.getFirst(), 5_000L));
        });
        assertThat(phases).as("vehicles are spread over the interval").hasSizeGreaterThan(byVehicle.size() / 2);
    }

    /** Every stop is reported as observed by the TripUpdate sent when the vehicle arrives there (DOC-25 §6.1). */
    @Test
    void sendsTripUpdatesPeriodicallyAndOnArrival() {
        List<OutboundMessage> sent = run(42, 1.0).sent();

        long periodic = 0;
        long onArrival = 0;
        for (OutboundMessage m : sent) {
            if (!m.topic().equals(MessageFactory.TRIP_UPDATES)) {
                continue;
            }
            Envelope<?> e = envelope(m);
            TripUpdate tu = (TripUpdate) e.payload();
            long t = e.eventTimestamp().toEpochMilli();
            checkInvariants(tu.stopTimeUpdates(), t);
            List<StopTimeUpdate> observed = tu.stopTimeUpdates().stream()
                    .filter(u -> u.arrival() != null && !u.arrival().time().isAfter(e.eventTimestamp()))
                    .toList();
            if (Math.floorMod(t - Emitter.phase(tu.tripId(), 30_000), 30_000) == 0) {
                periodic++;
                assertThat(observed)
                        .as("stops were already reported on arrival")
                        .isEmpty();
            } else {
                onArrival++;
                assertThat(observed).isNotEmpty();
                assertThat(observed.getLast().arrival().time()).isEqualTo(e.eventTimestamp());
            }
        }
        assertThat(periodic).isPositive();
        assertThat(onArrival).isPositive();
    }

    @Test
    void usesTheRouteAsKeyAndSetsTheHeaders() {
        for (OutboundMessage m : run(42, 1.0).sent()) {
            JsonNode json = MessageJson.mapper().readTree(m.value());
            assertThat(m.key()).isEqualTo(json.path("payload").path("route_id").asString());
            assertThat(m.headers())
                    .containsEntry(
                            MessageFactory.ENTITY_TYPE_HEADER,
                            json.path("entity_type").asString())
                    .containsEntry(
                            MessageFactory.SCHEMA_VERSION_HEADER,
                            json.path("schema_version").asString());
            assertThat(m.ledger().payloadHash()).hasSize(64);
            assertThat(m.ledger().businessKeys()).isNotEmpty();
        }
    }

    @Test
    void mixesSchemaVersionsOfVehiclePositions() {
        Map<Integer, Long> versions = new HashMap<>();
        for (OutboundMessage m : run(42, 1.0).sent()) {
            if (m.ledger().entityType().equals(EntityType.VEHICLE_POSITION.name())) {
                versions.merge(m.ledger().schemaVersion(), 1L, Long::sum);
            }
        }
        long total = versions.values().stream().mapToLong(Long::longValue).sum();
        assertThat(versions.get(2) / (double) total).isBetween(0.4, 0.6);
    }

    @Test
    void vehiclePositionBusinessKeysAreUnique() {
        Set<String> keys = new HashSet<>();
        for (OutboundMessage m : run(42, 1.0).sent()) {
            if (m.topic().equals(MessageFactory.VEHICLE_POSITIONS)) {
                assertThat(keys.add(m.ledger().businessKeys().getFirst())).isTrue();
            }
        }
    }

    /** T-06: the same seed and business time give the same messages. */
    @Test
    void isDeterministic() {
        assertThat(fingerprint(run(42, 1.0).sent()))
                .isEqualTo(fingerprint(run(42, 1.0).sent()));
        assertThat(fingerprint(run(42, 1.0).sent()))
                .isNotEqualTo(fingerprint(run(7, 1.0).sent()));
    }

    /** DOC-25 §6.5: the rate multiplier divides the intervals; 0 pauses. */
    @Test
    void theRateMultiplierScalesThePositionRate() {
        long normal = count(run(42, 1.0).sent(), MessageFactory.VEHICLE_POSITIONS);
        long doubled = count(run(42, 2.0).sent(), MessageFactory.VEHICLE_POSITIONS);

        assertThat(doubled).isBetween(normal * 19 / 10, normal * 21 / 10);
        assertThat(run(42, 0).sent()).isEmpty();
    }

    @Test
    void resumingAfterAPauseStartsWithoutABacklog() {
        EmitterHarness harness = new EmitterHarness(Feeds.mini(), PEAK, 42, 0).run(Duration.ofMinutes(5));
        harness.rate().set(1.0, null);
        harness.run(Duration.ofSeconds(5));

        assertThat(harness.sent())
                .isNotEmpty()
                .allSatisfy(m -> assertThat(m.ledger().eventTimestamp())
                        .isAfter(harness.clock().instant().minusSeconds(6)));
    }

    private static Envelope<?> envelope(OutboundMessage m) {
        return MessageJson.toEnvelope(MessageJson.mapper().readTree(m.value()));
    }

    private static long count(List<OutboundMessage> sent, String topic) {
        return sent.stream().filter(m -> m.topic().equals(topic)).count();
    }

    private static List<String> fingerprint(List<OutboundMessage> sent) {
        return sent.stream()
                .map(m -> m.topic() + "|" + m.key() + "|" + m.ledger().payloadHash())
                .toList();
    }

    private static void checkInvariants(List<StopTimeUpdate> updates, long t) {
        int previous = -1;
        int predicted = 0;
        for (StopTimeUpdate u : updates) {
            assertThat(u.stopSequence()).isGreaterThan(previous);
            previous = u.stopSequence();
            assertThat(u.arrival() != null || u.departure() != null).isTrue();
            if (u.arrival() != null && u.arrival().time().toEpochMilli() > t) {
                predicted++;
            }
        }
        assertThat(predicted).isLessThanOrEqualTo(10);
    }
}
