package dev.pti.etl.core.gtfsrt;

import static dev.pti.etl.testing.EtlFixtures.context;
import static dev.pti.etl.testing.EtlFixtures.message;
import static dev.pti.etl.testing.EtlFixtures.tripUpdate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.pti.common.dq.DlqStage;
import dev.pti.common.error.DataException;
import dev.pti.etl.core.EtlSource;
import dev.pti.etl.core.TripUpdateRow;
import dev.pti.etl.core.WriteSet;
import dev.pti.etl.rules.RuleContext;
import dev.pti.etl.testing.EtlFixtures;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** DOC-20 §4.3 and the TripUpdate cases of DOC-16 §8. */
class TripUpdateProcessorTest {

    private final TripUpdateProcessor processor = Processors.tripUpdates();

    private WriteSet process(ObjectNode json) {
        return process(json, context());
    }

    private WriteSet process(ObjectNode json, RuleContext context) {
        return processor.process(message(EtlSource.GTFS_RT_TRIP_UPDATE, json), context);
    }

    private static ObjectNode stop(ObjectNode json, int index) {
        return (ObjectNode) ((ArrayNode) json.get("payload").get("stop_time_updates")).get(index);
    }

    private DataException rejected(ObjectNode json) {
        Throwable thrown = catchThrowable(() -> process(json));
        assertThat(thrown).isInstanceOf(DataException.class);
        return (DataException) thrown;
    }

    @Test
    void oneRowPerStop() {
        WriteSet set = process(tripUpdate());

        assertThat(set.businessKey()).isEqualTo("2026-09-29|1361959");
        assertThat(set.eventTimestamp()).isEqualTo(Instant.parse("2026-09-29T21:19:30Z"));
        assertThat(set.vehicleIds()).containsExactly("2050");
        assertThat(set.tripUpdates()).hasSize(2);

        TripUpdateRow passed = set.tripUpdates().get(0);
        assertThat(passed.serviceDate()).isEqualTo(LocalDate.parse("2026-09-29"));
        assertThat(passed.stopSequence()).isEqualTo(14);
        assertThat(passed.delaySeconds()).isEqualTo(192);
        assertThat(passed.scheduledArrival()).isEqualTo(Instant.parse("2026-09-29T21:16:00Z"));
        assertThat(passed.departureTime()).isEqualTo(Instant.parse("2026-09-29T21:19:25Z"));
        assertThat(passed.observed()).isTrue();

        TripUpdateRow ahead = set.tripUpdates().get(1);
        assertThat(ahead.departureTime()).isNull();
        assertThat(ahead.observed()).isFalse();
        assertThat(ahead.key()).isEqualTo("2026-09-29|1361959|15");
    }

    @Test
    void aStopMissingFromTheScheduleDerivesItsScheduledArrival() {
        ObjectNode json = tripUpdate();
        stop(json, 1).put("stop_sequence", 19).put("stop_id", "1417");
        TripUpdateRow row = process(json).tripUpdates().get(1);
        assertThat(row.scheduledArrival()).isEqualTo(Instant.parse("2026-09-29T21:17:00Z"));
    }

    @Test
    void withoutArrivalTheDepartureDecides() {
        ObjectNode json = tripUpdate();
        stop(json, 0).remove("arrival");
        TripUpdateRow row = process(json).tripUpdates().getFirst();
        assertThat(row.delaySeconds()).isEqualTo(205);
        assertThat(row.arrivalTime()).isNull();
        assertThat(row.observed()).isTrue();
    }

    @Test
    void aSkippedStopIsNeverObserved() {
        ObjectNode json = tripUpdate();
        stop(json, 0).put("schedule_relationship", "SKIPPED").remove("arrival");
        stop(json, 0).remove("departure");
        assertThat(process(json).tripUpdates().getFirst().observed()).isFalse();
    }

    @Test
    void case09OneUnknownStopRejectsTheWholeMessage() {
        ObjectNode json = tripUpdate();
        stop(json, 1).put("stop_id", "999999");
        DataException e = rejected(json);
        assertThat(e.stage()).isEqualTo(DlqStage.QUALITY);
        assertThat(e.ruleId()).isEqualTo("DQ-04");
    }

    @Test
    void case17DelayOutOfRange() {
        ObjectNode json = tripUpdate();
        ((ObjectNode) stop(json, 1).get("arrival")).put("delay", 9000);
        assertThat(rejected(json).ruleId()).isEqualTo("DQ-08");
    }

    @Test
    void aVehiclePositionOnTheTripUpdateTopicBreaksTheSchema() {
        assertThat(rejected(EtlFixtures.vehiclePosition()).stage()).isEqualTo(DlqStage.SCHEMA);
    }

    @Test
    void withoutReferenceDataInReplayTheScheduleComesFromTheMessage() {
        ObjectNode json = tripUpdate();
        RuleContext lenient = new RuleContext(EtlFixtures.NOW, Duration.ZERO, true, EtlFixtures.referenceData());
        assertThat(process(json, lenient).tripUpdates()).hasSize(2);
    }

    @Test
    void businessKeyIsReadBestEffort() {
        assertThat(processor.businessKey(message(EtlSource.GTFS_RT_TRIP_UPDATE, tripUpdate())))
                .isEqualTo("2026-09-29|1361959");
        ObjectNode odd = tripUpdate();
        ((ObjectNode) odd.get("payload")).put("start_date", "yesterday");
        assertThat(processor.businessKey(message(EtlSource.GTFS_RT_TRIP_UPDATE, odd)))
                .isEqualTo("yesterday|1361959");
        ObjectNode none = tripUpdate();
        ((ObjectNode) none.get("payload")).remove("trip_id");
        assertThat(processor.businessKey(message(EtlSource.GTFS_RT_TRIP_UPDATE, none)))
                .isNull();
    }

    @Test
    void aTombstoneWritesNothing() {
        assertThat(processor
                        .process(EtlFixtures.tombstone(EtlSource.GTFS_RT_TRIP_UPDATE), context())
                        .isEmpty())
                .isTrue();
    }
}
