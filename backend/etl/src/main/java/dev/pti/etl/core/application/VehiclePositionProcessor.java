package dev.pti.etl.core.application;

import dev.pti.common.gtfs.GtfsTime;
import dev.pti.common.message.BusinessKey;
import dev.pti.common.message.EntityType;
import dev.pti.common.message.OccupancyStatus;
import dev.pti.common.message.VehiclePosition;
import dev.pti.etl.core.application.port.BestEffortKeys;
import dev.pti.etl.core.application.port.EnvelopeReader;
import dev.pti.etl.core.domain.EtlSource;
import dev.pti.etl.core.domain.InboundMessage;
import dev.pti.etl.core.domain.RealtimeFacts;
import dev.pti.etl.core.domain.RuleContext;
import dev.pti.etl.core.domain.RuleEngine;
import dev.pti.etl.core.domain.VehiclePositionRow;
import dev.pti.etl.core.domain.WriteSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** VehiclePosition v1 and v2 to one fact row and one latest-position row (DOC-20 §4.2). */
public final class VehiclePositionProcessor implements MessageProcessor {

    private final EnvelopeReader reader;
    private final RuleEngine<RealtimeFacts> rules;
    private final BestEffortKeys keys;

    public VehiclePositionProcessor(EnvelopeReader reader, RuleEngine<RealtimeFacts> rules, BestEffortKeys keys) {
        this.reader = reader;
        this.rules = rules;
        this.keys = keys;
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
                        read.envelope().producedAt(),
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
        return keys.of(message, fields -> {
            BestEffortKeys.Fields payload = fields.path("payload");
            String vehicle = payload.path("vehicle_id").asString();
            String event = fields.path("event_timestamp").asString();
            return vehicle == null || event == null ? null : vehicle + "|" + event;
        });
    }

    private static @Nullable Float toFloat(@Nullable Double value) {
        return value == null ? null : value.floatValue();
    }
}
