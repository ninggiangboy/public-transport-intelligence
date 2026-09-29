package dev.pti.etl.core.gtfsrt;

import dev.pti.common.gtfs.GtfsTime;
import dev.pti.common.message.BusinessKey;
import dev.pti.common.message.EntityType;
import dev.pti.common.message.OccupancyStatus;
import dev.pti.common.message.VehiclePosition;
import dev.pti.etl.core.BestEffortKeys;
import dev.pti.etl.core.EtlSource;
import dev.pti.etl.core.InboundMessage;
import dev.pti.etl.core.MessageProcessor;
import dev.pti.etl.core.VehiclePositionRow;
import dev.pti.etl.core.WriteSet;
import dev.pti.etl.rules.RealtimeFacts;
import dev.pti.etl.rules.RuleContext;
import dev.pti.etl.rules.RuleEngine;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** VehiclePosition v1 and v2 to one fact row and one latest-position row (DOC-20 §4.2). */
public final class VehiclePositionProcessor implements MessageProcessor {

    private final EnvelopeReader reader;
    private final RuleEngine<RealtimeFacts> rules;

    public VehiclePositionProcessor(EnvelopeReader reader, RuleEngine<RealtimeFacts> rules) {
        this.reader = reader;
        this.rules = rules;
    }

    @Override
    public EtlSource source() {
        return EtlSource.GTFS_RT_VEHICLE_POSITION;
    }

    @Override
    public WriteSet process(InboundMessage message, RuleContext context) {
        if (message.isTombstone()) {
            return WriteSet.empty(message);
        }
        EnvelopeReader.Read read = reader.read(message, EntityType.VEHICLE_POSITION);
        VehiclePosition vp = (VehiclePosition) read.envelope().payload();
        Instant event = read.envelope().eventTimestamp().truncatedTo(ChronoUnit.MILLIS);
        LocalDate serviceDate = GtfsTime.parseServiceDate(vp.startDate());
        rules.check(
                new RealtimeFacts(
                        vp.routeId(),
                        vp.tripId(),
                        vp.directionId().shortValue(),
                        List.of(vp.stopId()),
                        serviceDate,
                        event,
                        vp.lat(),
                        vp.lon(),
                        List.of()),
                context);
        OccupancyStatus occupancy = vp.occupancy();
        VehiclePositionRow row = new VehiclePositionRow(
                serviceDate,
                vp.vehicleId(),
                event,
                vp.tripId(),
                vp.routeId(),
                vp.directionId().shortValue(),
                vp.lat(),
                vp.lon(),
                toFloat(vp.bearing()),
                toFloat(vp.speedMps()),
                vp.currentStopSequence(),
                vp.stopId(),
                vp.currentStatus().name(),
                occupancy == null ? null : occupancy.name(),
                read.envelope().schemaVersion().shortValue(),
                read.hash());
        return WriteSet.vehiclePosition(message, read.hash(), BusinessKey.vehiclePosition(vp.vehicleId(), event), row);
    }

    @Override
    public @Nullable String businessKey(InboundMessage message) {
        return BestEffortKeys.of(message, tree -> {
            JsonNode payload = tree.path("payload");
            String vehicle = payload.path("vehicle_id").asString(null);
            String event = tree.path("event_timestamp").asString(null);
            return vehicle == null || event == null ? null : vehicle + "|" + event;
        });
    }

    private static @Nullable Float toFloat(@Nullable Double value) {
        return value == null ? null : value.floatValue();
    }
}
