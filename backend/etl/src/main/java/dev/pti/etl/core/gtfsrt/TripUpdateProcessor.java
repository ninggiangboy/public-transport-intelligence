package dev.pti.etl.core.gtfsrt;

import dev.pti.common.gtfs.GtfsTime;
import dev.pti.common.message.EntityType;
import dev.pti.common.message.ScheduleRelationship;
import dev.pti.common.message.StopTimeEvent;
import dev.pti.common.message.StopTimeUpdate;
import dev.pti.common.message.TripUpdate;
import dev.pti.etl.core.BestEffortKeys;
import dev.pti.etl.core.EtlSource;
import dev.pti.etl.core.InboundMessage;
import dev.pti.etl.core.MessageProcessor;
import dev.pti.etl.core.TripUpdateRow;
import dev.pti.etl.core.WriteSet;
import dev.pti.etl.reference.ReferenceData;
import dev.pti.etl.rules.RealtimeFacts;
import dev.pti.etl.rules.RuleContext;
import dev.pti.etl.rules.RuleEngine;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** TripUpdate v1 to one fact row per stop time update (DR-13, DOC-20 §4.3). */
public final class TripUpdateProcessor implements MessageProcessor {

    private final EnvelopeReader reader;
    private final RuleEngine<RealtimeFacts> rules;

    public TripUpdateProcessor(EnvelopeReader reader, RuleEngine<RealtimeFacts> rules) {
        this.reader = reader;
        this.rules = rules;
    }

    @Override
    public EtlSource source() {
        return EtlSource.GTFS_RT_TRIP_UPDATE;
    }

    @Override
    public WriteSet process(InboundMessage message, RuleContext context) {
        if (message.isTombstone()) {
            return WriteSet.empty(message);
        }
        EnvelopeReader.Read read = reader.read(message, EntityType.TRIP_UPDATE);
        TripUpdate tu = (TripUpdate) read.envelope().payload();
        Instant event = read.envelope().eventTimestamp().truncatedTo(ChronoUnit.MILLIS);
        LocalDate serviceDate = GtfsTime.parseServiceDate(tu.startDate());
        List<String> stops = new ArrayList<>();
        List<Integer> delays = new ArrayList<>();
        for (StopTimeUpdate u : tu.stopTimeUpdates()) {
            stops.add(u.stopId());
            StopTimeEvent arrival = u.arrival();
            StopTimeEvent departure = u.departure();
            if (arrival != null) {
                delays.add(arrival.delay());
            }
            if (departure != null) {
                delays.add(departure.delay());
            }
        }
        rules.check(
                new RealtimeFacts(
                        tu.routeId(),
                        tu.tripId(),
                        tu.directionId().shortValue(),
                        stops,
                        serviceDate,
                        event,
                        read.envelope().producedAt(),
                        null,
                        null,
                        delays),
                context);
        ReferenceData reference = context.reference();
        List<TripUpdateRow> rows = new ArrayList<>(tu.stopTimeUpdates().size());
        for (StopTimeUpdate u : tu.stopTimeUpdates()) {
            rows.add(row(tu, u, serviceDate, event, read.hash(), reference));
        }
        String key = serviceDate + "|" + tu.tripId();
        return WriteSet.tripUpdate(message, read.hash(), key, event, rows);
    }

    private static TripUpdateRow row(
            TripUpdate tu,
            StopTimeUpdate u,
            LocalDate serviceDate,
            Instant event,
            String hash,
            @Nullable ReferenceData reference) {
        StopTimeEvent arrival = u.arrival();
        StopTimeEvent departure = u.departure();
        Instant arrivalTime = arrival == null ? null : arrival.time().truncatedTo(ChronoUnit.MILLIS);
        Instant departureTime = departure == null ? null : departure.time().truncatedTo(ChronoUnit.MILLIS);
        Integer delay = arrival != null ? arrival.delay() : (departure != null ? departure.delay() : null);
        Instant scheduled =
                reference == null ? null : reference.scheduledArrival(tu.tripId(), u.stopSequence(), serviceDate);
        if (scheduled == null) {
            // Not in the ACTIVE feed's schedule: derive it from the event as the source did (DOC-13 §7.2).
            if (arrival != null) {
                scheduled = arrivalTime.minusSeconds(arrival.delay());
            } else if (departure != null) {
                scheduled = departureTime.minusSeconds(departure.delay());
            }
        }
        Instant observedAt = arrivalTime != null ? arrivalTime : departureTime;
        boolean observed = u.scheduleRelationship() == ScheduleRelationship.SCHEDULED
                && observedAt != null
                && !observedAt.isAfter(event);
        return new TripUpdateRow(
                serviceDate,
                tu.tripId(),
                u.stopSequence(),
                tu.routeId(),
                tu.directionId().shortValue(),
                u.stopId(),
                tu.vehicleId(),
                u.scheduleRelationship().name(),
                scheduled,
                arrivalTime,
                departureTime,
                delay,
                observed,
                event,
                hash);
    }

    @Override
    public @Nullable String businessKey(InboundMessage message) {
        return BestEffortKeys.of(message, tree -> {
            JsonNode payload = tree.path("payload");
            String trip = payload.path("trip_id").asString(null);
            String startDate = payload.path("start_date").asString(null);
            if (trip == null || startDate == null) {
                return null;
            }
            try {
                return GtfsTime.parseServiceDate(startDate) + "|" + trip;
            } catch (IllegalArgumentException e) {
                return startDate + "|" + trip;
            }
        });
    }
}
