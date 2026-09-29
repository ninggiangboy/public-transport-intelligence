package dev.pti.etl.core.gtfsrt;

import static dev.pti.etl.testing.EtlFixtures.NOW;
import static dev.pti.etl.testing.EtlFixtures.context;
import static dev.pti.etl.testing.EtlFixtures.message;
import static dev.pti.etl.testing.EtlFixtures.referenceData;
import static dev.pti.etl.testing.EtlFixtures.vehiclePosition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.common.dq.DlqStage;
import dev.pti.common.error.DataException;
import dev.pti.common.error.FatalException;
import dev.pti.common.time.Timestamps;
import dev.pti.etl.core.EtlSource;
import dev.pti.etl.core.VehiclePositionRow;
import dev.pti.etl.core.WriteSet;
import dev.pti.etl.rules.RuleContext;
import dev.pti.etl.testing.EtlFixtures;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** DOC-20 §4.2 and the VehiclePosition cases of DOC-16 §8. */
class VehiclePositionProcessorTest {

    private final VehiclePositionProcessor processor = Processors.vehiclePositions();

    private WriteSet process(ObjectNode json) {
        return process(json, context());
    }

    private WriteSet process(ObjectNode json, RuleContext context) {
        return processor.process(message(EtlSource.GTFS_RT_VEHICLE_POSITION, json), context);
    }

    private static ObjectNode payload(ObjectNode json) {
        return (ObjectNode) json.get("payload");
    }

    private DataException rejected(ObjectNode json) {
        return rejected(json, context());
    }

    private DataException rejected(ObjectNode json, RuleContext context) {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> process(json, context));
        assertThat(thrown).isInstanceOf(DataException.class);
        return (DataException) thrown;
    }

    @Test
    void mapsTheExampleMessage() {
        WriteSet set = process(vehiclePosition());

        VehiclePositionRow row = set.vehiclePositions().getFirst();
        assertThat(row.serviceDate()).isEqualTo(LocalDate.parse("2026-09-29"));
        assertThat(row.vehicleId()).isEqualTo("2050");
        assertThat(row.eventTimestamp()).isEqualTo(Instant.parse("2026-09-29T21:19:05Z"));
        assertThat(row.occupancyStatus()).isEqualTo("FEW_SEATS_AVAILABLE");
        assertThat(row.schemaVersion()).isEqualTo((short) 2);
        assertThat(row.bearing()).isEqualTo(335.0f);
        assertThat(row.payloadHash()).hasSize(64).isEqualTo(set.messageHash());
        assertThat(set.vehicleIds()).containsExactly("2050");
        assertThat(set.version()).isEqualTo(row.eventTimestamp().toEpochMilli());
    }

    @Test
    void theSameMessageMapsToAnEqualWriteSet() {
        var message = message(EtlSource.GTFS_RT_VEHICLE_POSITION, vehiclePosition());
        assertThat(processor.process(message, context())).isEqualTo(processor.process(message, context()));
    }

    @Test
    void acceptsVersionOneWithoutOccupancy() {
        ObjectNode json = vehiclePosition();
        json.put("schema_version", 1);
        payload(json).remove("occupancy_status");
        assertThat(process(json).vehiclePositions().getFirst().occupancyStatus())
                .isNull();
    }

    @Test
    void case01MissingVehicleIdBreaksTheSchema() {
        ObjectNode json = vehiclePosition();
        payload(json).remove("vehicle_id");
        DataException e = rejected(json);
        assertThat(e.stage()).isEqualTo(DlqStage.SCHEMA);
        assertThat(e.ruleId()).isEqualTo("DQ-01");
        assertThat(e.getMessage()).contains("vehicle_id");
    }

    @Test
    void case02UnknownSchemaVersionBreaksTheSchema() {
        ObjectNode json = vehiclePosition();
        json.put("schema_version", 3);
        DataException e = rejected(json);
        assertThat(e.stage()).isEqualTo(DlqStage.SCHEMA);
        assertThat(e.getMessage()).contains("Unsupported schema_version 3");
    }

    @Test
    void aTripUpdateOnThePositionTopicBreaksTheSchema() {
        assertThat(rejected(EtlFixtures.tripUpdate()).stage()).isEqualTo(DlqStage.SCHEMA);
    }

    @Test
    void malformedJsonIsADeserializationError() {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() ->
                processor.process(message(EtlSource.GTFS_RT_VEHICLE_POSITION, "{\"schema_version\":"), context()));
        assertThat(thrown)
                .isInstanceOfSatisfying(
                        DataException.class, e -> assertThat(e.stage()).isEqualTo(DlqStage.DESERIALIZE));
    }

    @Test
    void invalidUtf8IsADeserializationError() {
        byte[] bytes = {'{', (byte) 0xFF, (byte) 0xC3, '}'};
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> processor.process(message(EtlSource.GTFS_RT_VEHICLE_POSITION, bytes), context()));
        assertThat(thrown).isInstanceOfSatisfying(DataException.class, e -> {
            assertThat(e.stage()).isEqualTo(DlqStage.DESERIALIZE);
            assertThat(e.errorClass()).isEqualTo("MalformedInputException");
        });
    }

    @Test
    void aTombstoneWritesNothing() {
        assertThat(processor
                        .process(EtlFixtures.tombstone(EtlSource.GTFS_RT_VEHICLE_POSITION), context())
                        .isEmpty())
                .isTrue();
    }

    @Test
    void case08UnknownRoute() {
        ObjectNode json = vehiclePosition();
        payload(json).put("route_id", "R-UNKNOWN-1");
        DataException e = rejected(json);
        assertThat(e.stage()).isEqualTo(DlqStage.QUALITY);
        assertThat(e.ruleId()).isEqualTo("DQ-03");
    }

    @Test
    void unknownStop() {
        ObjectNode json = vehiclePosition();
        payload(json).put("stop_id", "999999");
        assertThat(rejected(json).ruleId()).isEqualTo("DQ-04");
    }

    @Test
    void case10TripOfAnotherRoute() {
        ObjectNode json = vehiclePosition();
        payload(json).put("route_id", "901");
        assertThat(rejected(json).ruleId()).isEqualTo("DQ-05");
    }

    @Test
    void unknownTrip() {
        ObjectNode json = vehiclePosition();
        payload(json).put("trip_id", "999");
        assertThat(rejected(json).ruleId()).isEqualTo("DQ-05");
    }

    @Test
    void case11OutsideTheServiceArea() {
        ObjectNode json = vehiclePosition();
        payload(json).put("lat", 40.7128).put("lon", -74.0060);
        assertThat(rejected(json).ruleId()).isEqualTo("DQ-06");
    }

    @Test
    void case12InsideTheMargin() {
        ObjectNode json = vehiclePosition();
        payload(json).put("lat", 45.05);
        assertThat(process(json).vehiclePositions()).hasSize(1);
    }

    @Test
    void case13EventTwoHoursAhead() {
        ObjectNode json = vehiclePosition();
        json.put("event_timestamp", "2026-09-29T23:20:00.000Z");
        assertThat(rejected(json).ruleId()).isEqualTo("DQ-07");
    }

    @Test
    void case14EventFiftyFiveMinutesBehind() {
        ObjectNode json = vehiclePosition();
        json.put("event_timestamp", "2026-09-29T20:25:00.000Z");
        assertThat(process(json).vehiclePositions()).hasSize(1);
    }

    @Test
    void case15ReplaySkipsTheClockSkewRule() {
        ObjectNode json = vehiclePosition();
        json.put("event_timestamp", "2026-09-29T23:20:00.000Z");
        assertThat(process(json, EtlFixtures.replayContext()).vehiclePositions())
                .hasSize(1);
    }

    @Test
    void case16SkewIsMeasuredOnTheBusinessClock() {
        Instant businessNow = NOW.minus(Duration.ofHours(12));
        ObjectNode json = vehiclePosition();
        json.put("event_timestamp", Timestamps.format(businessNow));
        RuleContext shifted = new RuleContext(businessNow, false, referenceData());
        assertThat(process(json, shifted).vehiclePositions()).hasSize(1);
    }

    @Test
    void case18ATripAfterMidnightBelongsToThePreviousServiceDay() {
        Instant event = Instant.parse("2026-09-30T06:30:00Z");
        ObjectNode json = vehiclePosition();
        json.put("event_timestamp", Timestamps.format(event));
        RuleContext context = new RuleContext(event, false, referenceData());
        assertThat(process(json, context).vehiclePositions().getFirst().serviceDate())
                .isEqualTo(LocalDate.parse("2026-09-29"));
    }

    @Test
    void case19ServiceDateFarFromTheEvent() {
        ObjectNode json = vehiclePosition();
        payload(json).put("start_date", "20260925");
        assertThat(rejected(json).ruleId()).isEqualTo("DQ-09");
    }

    @Test
    void case28OnlyTheFirstViolationCounts() {
        ObjectNode json = vehiclePosition();
        payload(json).put("route_id", "R-UNKNOWN-1").put("lat", 40.7128).put("lon", -74.0060);
        assertThat(rejected(json).ruleId()).isEqualTo("DQ-03");
    }

    @Test
    void aDisabledRuleIsNotEvaluated() {
        var dq = EtlFixtures.dq(java.util.Map.of("DQ-03", new dev.pti.etl.config.DqProperties.Rule(false)));
        var lenient = new VehiclePositionProcessor(
                Processors.READER, new dev.pti.etl.rules.RuleEngine<>(dev.pti.etl.rules.RealtimeRules.all(dq), dq));
        ObjectNode json = vehiclePosition();
        payload(json).put("route_id", "R-UNKNOWN-1").put("trip_id", "999");
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> lenient.process(message(EtlSource.GTFS_RT_VEHICLE_POSITION, json), context()));
        assertThat(thrown)
                .isInstanceOfSatisfying(
                        DataException.class, e -> assertThat(e.ruleId()).isEqualTo("DQ-05"));
    }

    @Test
    void withoutAnActiveFeedTheRulesCannotRun() {
        assertThatThrownBy(() -> process(vehiclePosition(), new RuleContext(NOW, false, null)))
                .isInstanceOf(FatalException.class);
    }

    @Test
    void businessKeyIsReadBestEffort() {
        assertThat(processor.businessKey(message(EtlSource.GTFS_RT_VEHICLE_POSITION, vehiclePosition())))
                .isEqualTo("2050|2026-09-29T21:19:05.000Z");
        assertThat(processor.businessKey(message(EtlSource.GTFS_RT_VEHICLE_POSITION, "not json")))
                .isNull();
        assertThat(processor.businessKey(EtlFixtures.tombstone(EtlSource.GTFS_RT_VEHICLE_POSITION)))
                .isNull();
    }
}
