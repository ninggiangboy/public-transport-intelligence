package dev.pti.common.message;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

class BeanValidationTest {

    private static final ValidatorFactory FACTORY = Validation.byDefaultProvider()
            .configure()
            .messageInterpolator(new ParameterMessageInterpolator())
            .buildValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void close() {
        FACTORY.close();
    }

    @Test
    void acceptsTheDocumentedExamples() {
        assertThat(VALIDATOR.validate(SampleMessages.vehiclePositionEnvelope())).isEmpty();
        assertThat(VALIDATOR.validate(SampleMessages.tripUpdateEnvelope())).isEmpty();
        assertThat(VALIDATOR.validate(SampleMessages.vehiclePositionV1())).isEmpty();
    }

    @Test
    void checksEveryVehiclePositionField() {
        VehiclePositionV1 invalid = new VehiclePositionV1(
                "", "1361959", "18", 2, "2026-09-29", 91.0, -181.0, 361.0, -1.0, -1, "51821", null);

        assertThat(paths(VALIDATOR.validate(invalid)))
                .containsExactlyInAnyOrder(
                        "vehicleId",
                        "directionId",
                        "startDate",
                        "lat",
                        "lon",
                        "bearing",
                        "speedMps",
                        "currentStopSequence",
                        "currentStatus");
    }

    @Test
    void cascadesIntoThePayload() {
        Envelope<VehiclePositionV1> envelope = new Envelope<>(
                1,
                UUID.randomUUID(),
                EntityType.VEHICLE_POSITION,
                "Bad Source",
                Instant.EPOCH,
                Instant.EPOCH,
                new VehiclePositionV1(
                        "2050",
                        null,
                        "18",
                        0,
                        "20260929",
                        44.8,
                        -93.2,
                        null,
                        null,
                        15,
                        "51821",
                        VehicleStopStatus.STOPPED_AT));

        assertThat(paths(VALIDATOR.validate(envelope))).containsExactlyInAnyOrder("source", "payload.tripId");
    }

    @Test
    void rejectsAnEnvelopeWhoseTypeDoesNotMatchThePayload() {
        Envelope<VehiclePositionV1> wrongVersion = new Envelope<>(
                2,
                UUID.randomUUID(),
                EntityType.VEHICLE_POSITION,
                Envelope.SIMULATOR_SOURCE,
                Instant.EPOCH,
                Instant.EPOCH,
                SampleMessages.vehiclePositionV1());
        Envelope<VehiclePositionV1> unknownVersion = new Envelope<>(
                9,
                UUID.randomUUID(),
                EntityType.VEHICLE_POSITION,
                Envelope.SIMULATOR_SOURCE,
                Instant.EPOCH,
                Instant.EPOCH,
                SampleMessages.vehiclePositionV1());

        assertThat(paths(VALIDATOR.validate(wrongVersion))).containsExactly("consistent");
        assertThat(paths(VALIDATOR.validate(unknownVersion))).containsExactly("consistent");
    }

    @Test
    void requiresIncreasingStopSequence() {
        TripUpdateV1 valid = SampleMessages.tripUpdateV1();
        List<StopTimeUpdate> reversed = new ArrayList<>(valid.stopTimeUpdates()).reversed();
        List<StopTimeUpdate> repeated = List.of(
                valid.stopTimeUpdates().getFirst(), valid.stopTimeUpdates().getFirst());

        assertThat(paths(VALIDATOR.validate(withUpdates(valid, reversed)))).containsExactly("stopSequenceIncreasing");
        assertThat(paths(VALIDATOR.validate(withUpdates(valid, repeated)))).containsExactly("stopSequenceIncreasing");
        assertThat(paths(VALIDATOR.validate(withUpdates(valid, List.of())))).containsExactly("stopTimeUpdates");
    }

    @Test
    void checksEventsAgainstTheScheduleRelationship() {
        StopTimeEvent event = new StopTimeEvent(Instant.EPOCH, 0);
        TripUpdateV1 valid = SampleMessages.tripUpdateV1();

        List<StopTimeUpdate> updates = List.of(
                new StopTimeUpdate(1, "A", null, null, ScheduleRelationship.SCHEDULED),
                new StopTimeUpdate(2, "B", null, event, ScheduleRelationship.SKIPPED),
                new StopTimeUpdate(3, "C", event, null, ScheduleRelationship.NO_DATA),
                new StopTimeUpdate(4, "D", null, null, ScheduleRelationship.SKIPPED),
                new StopTimeUpdate(5, "E", null, new StopTimeEvent(null, null), ScheduleRelationship.SCHEDULED),
                new StopTimeUpdate(6, "F", null, null, null));

        assertThat(paths(VALIDATOR.validate(withUpdates(valid, updates))))
                .containsExactlyInAnyOrder(
                        "stopTimeUpdates[0].eventsConsistent",
                        "stopTimeUpdates[1].eventsConsistent",
                        "stopTimeUpdates[2].eventsConsistent",
                        "stopTimeUpdates[4].departure.time",
                        "stopTimeUpdates[4].departure.delay",
                        "stopTimeUpdates[5].scheduleRelationship");
    }

    @Test
    void acceptsANullListWithoutFailingTheCrossChecks() {
        TripUpdateV1 noUpdates = new TripUpdateV1("1", "18", 0, "20260929", "2050", null);

        assertThat(paths(VALIDATOR.validate(noUpdates))).containsExactly("stopTimeUpdates");
    }

    @Test
    void exposesTheOccupancyOfEitherVersion() {
        assertThat(SampleMessages.vehiclePositionV1().occupancy()).isNull();
        assertThat(SampleMessages.vehiclePositionV2().occupancy()).isEqualTo(OccupancyStatus.FEW_SEATS_AVAILABLE);
        assertThat(SampleMessages.vehiclePositionV2().routeId()).isEqualTo("18");
        assertThat(SampleMessages.tripUpdateV1().routeId()).isEqualTo("18");
    }

    private static TripUpdateV1 withUpdates(TripUpdateV1 t, List<StopTimeUpdate> updates) {
        return new TripUpdateV1(t.tripId(), t.routeId(), t.directionId(), t.startDate(), t.vehicleId(), updates);
    }

    private static <T> List<String> paths(Set<ConstraintViolation<T>> violations) {
        return violations.stream().map(v -> v.getPropertyPath().toString()).toList();
    }
}
