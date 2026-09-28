package dev.pti.common.message;

import dev.pti.common.json.MessageJson;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.node.ObjectNode;

/** The DOC-09 examples, as JSON and as DTOs. */
final class SampleMessages {

    static final String VEHICLE_POSITION_V2 = "messages/vehicle-position.v2.json";
    static final String TRIP_UPDATE_V1 = "messages/trip-update.v1.json";

    private SampleMessages() {}

    static ObjectNode json(String resource) {
        try (InputStream in = SampleMessages.class.getClassLoader().getResourceAsStream(resource)) {
            return (ObjectNode) MessageJson.mapper().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static VehiclePositionV1 vehiclePositionV1() {
        return new VehiclePositionV1(
                "2050",
                "1361959",
                "18",
                0,
                "20260929",
                44.82312,
                -93.28961,
                335.0,
                7.8,
                15,
                "51821",
                VehicleStopStatus.IN_TRANSIT_TO);
    }

    static VehiclePositionV2 vehiclePositionV2() {
        return new VehiclePositionV2(
                "2050",
                "1361959",
                "18",
                0,
                "20260929",
                44.82312,
                -93.28961,
                335.0,
                7.8,
                15,
                "51821",
                VehicleStopStatus.IN_TRANSIT_TO,
                OccupancyStatus.FEW_SEATS_AVAILABLE);
    }

    static TripUpdateV1 tripUpdateV1() {
        return new TripUpdateV1(
                "1361959",
                "18",
                0,
                "20260929",
                "2050",
                List.of(
                        new StopTimeUpdate(
                                14,
                                "51631",
                                new StopTimeEvent(Instant.parse("2026-09-29T21:19:12Z"), 192),
                                new StopTimeEvent(Instant.parse("2026-09-29T21:19:25Z"), 205),
                                ScheduleRelationship.SCHEDULED),
                        new StopTimeUpdate(
                                15,
                                "51821",
                                new StopTimeEvent(Instant.parse("2026-09-29T21:20:20Z"), 200),
                                null,
                                ScheduleRelationship.SCHEDULED)));
    }

    static Envelope<VehiclePositionV2> vehiclePositionEnvelope() {
        return Envelope.of(
                UUID.fromString("0192f4a6-7c1e-7b3a-9d2e-5f0a1b2c3d4e"),
                Envelope.SIMULATOR_SOURCE,
                Instant.parse("2026-09-29T21:19:05Z"),
                Instant.parse("2026-09-29T21:19:05.412Z"),
                vehiclePositionV2());
    }

    static Envelope<TripUpdateV1> tripUpdateEnvelope() {
        return Envelope.of(
                UUID.fromString("0192f4a6-8a10-7d44-b0c1-22aa4c1e9f10"),
                Envelope.SIMULATOR_SOURCE,
                Instant.parse("2026-09-29T21:19:30Z"),
                Instant.parse("2026-09-29T21:19:30.107Z"),
                tripUpdateV1());
    }
}
