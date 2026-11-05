package dev.pti.simulator.emit.domain;

import com.github.f4b6a3.uuid.UuidCreator;
import dev.pti.common.gtfs.GtfsTime;
import dev.pti.common.message.BusinessKey;
import dev.pti.common.message.Envelope;
import dev.pti.common.message.EnvelopeHasher;
import dev.pti.common.message.Payload;
import dev.pti.common.message.PayloadType;
import dev.pti.common.message.StopTimeUpdate;
import dev.pti.common.message.TripUpdateV1;
import dev.pti.common.message.VehiclePosition;
import dev.pti.common.message.VehiclePositionV1;
import dev.pti.common.message.VehiclePositionV2;
import dev.pti.common.time.BusinessClock;
import dev.pti.simulator.feed.domain.TripSchedule;
import dev.pti.simulator.ledger.domain.LedgerEntry;
import dev.pti.simulator.motion.domain.Occupancy;
import dev.pti.simulator.motion.domain.Position;
import dev.pti.simulator.motion.domain.Seeds;
import dev.pti.simulator.motion.domain.TripRun;
import dev.pti.simulator.motion.domain.VehicleRun;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Builds VehiclePosition and TripUpdate messages (DOC-25 §6.2, §6.3; DOC-09). */
public final class MessageFactory {

    public static final String VEHICLE_POSITIONS = "gtfs.vehicle_positions";
    public static final String TRIP_UPDATES = "gtfs.trip_updates";
    public static final String SCHEMA_VERSION_HEADER = "schema_version";
    public static final String ENTITY_TYPE_HEADER = "entity_type";

    private final BusinessClock clock;
    private final long seed;
    private final double gpsNoise;
    private final int v2PerMille;
    private final int lookaheadStops;
    private final EnvelopeHasher hasher;
    private final EnvelopeSerializer serializer;

    public MessageFactory(
            BusinessClock clock,
            long seed,
            double gpsNoise,
            double v2Ratio,
            int lookaheadStops,
            EnvelopeHasher hasher,
            EnvelopeSerializer serializer) {
        this.clock = clock;
        this.seed = seed;
        this.gpsNoise = gpsNoise;
        this.v2PerMille = (int) Math.round(v2Ratio * 1000);
        this.lookaheadStops = lookaheadStops;
        this.hasher = hasher;
        this.serializer = serializer;
    }

    /** The position of {@code vehicle} at {@code t}. Call {@link VehicleRun#advanceTo} with {@code t} first. */
    public OutboundMessage vehiclePosition(VehicleRun vehicle, long t) {
        TripRun run = vehicle.run();
        TripSchedule s = run.schedule();
        TripRun.Motion motion = run.motion(t);
        Position p = Position.of(run, motion, seed, gpsNoise, vehicle.vehicleId(), t);
        String startDate = GtfsTime.formatServiceDate(run.serviceDate());
        int stopSequence = s.stopSequence(motion.stopIndex());
        String stopId = s.stop(motion.stopIndex()).id();
        VehiclePosition payload = v2(vehicle.vehicleId(), t)
                ? new VehiclePositionV2(
                        vehicle.vehicleId(),
                        s.tripId(),
                        s.routeId(),
                        s.directionId(),
                        startDate,
                        p.lat(),
                        p.lon(),
                        p.bearing(),
                        p.speed(),
                        stopSequence,
                        stopId,
                        motion.status(),
                        Occupancy.of(seed, vehicle.vehicleId(), run, motion))
                : new VehiclePositionV1(
                        vehicle.vehicleId(),
                        s.tripId(),
                        s.routeId(),
                        s.directionId(),
                        startDate,
                        p.lat(),
                        p.lon(),
                        p.bearing(),
                        p.speed(),
                        stopSequence,
                        stopId,
                        motion.status());
        return outbound(VEHICLE_POSITIONS, s.routeId(), t, payload);
    }

    /**
     * The TripUpdate of {@code vehicle}'s current trip at {@code t}, or empty when it would have no stop time
     * updates (the trip has ended and everything was reported). Advances the trip's reported stops (DOC-25 §6.3).
     */
    public Optional<OutboundMessage> tripUpdate(VehicleRun vehicle, long t) {
        TripRun run = vehicle.run();
        TripSchedule s = run.schedule();
        List<StopTimeUpdate> updates = run.tripUpdate(t, lookaheadStops);
        if (updates.isEmpty()) {
            return Optional.empty();
        }
        TripUpdateV1 payload = new TripUpdateV1(
                s.tripId(),
                s.routeId(),
                s.directionId(),
                GtfsTime.formatServiceDate(run.serviceDate()),
                vehicle.vehicleId(),
                updates);
        return Optional.of(outbound(TRIP_UPDATES, s.routeId(), t, payload));
    }

    /** Schema v2 for {@code v2-ratio} of the positions, chosen by {@code (vehicle_id, event_timestamp)}. */
    private boolean v2(String vehicleId, long t) {
        return Math.floorMod(Seeds.of(seed, "schema", vehicleId, t), 1000) < v2PerMille;
    }

    private OutboundMessage outbound(String topic, String routeId, long t, Payload payload) {
        UUID messageId = UuidCreator.getTimeOrderedEpoch();
        Envelope<Payload> envelope =
                Envelope.of(messageId, Envelope.SIMULATOR_SOURCE, Instant.ofEpochMilli(t), clock.realNow(), payload);
        PayloadType type = PayloadType.of(payload);
        LedgerEntry entry = new LedgerEntry(
                messageId,
                type.entityType().name(),
                BusinessKey.of(envelope),
                envelope.eventTimestamp(),
                envelope.producedAt(),
                type.schemaVersion(),
                hasher.hash(envelope),
                null,
                null,
                null);
        return new OutboundMessage(
                topic,
                routeId,
                serializer.serialize(envelope),
                Map.of(
                        SCHEMA_VERSION_HEADER,
                        Integer.toString(type.schemaVersion()),
                        ENTITY_TYPE_HEADER,
                        type.entityType().name()),
                entry);
    }
}
