package dev.pti.apitest;

import dev.pti.api.transit.domain.ArrivalCandidate;
import dev.pti.api.transit.domain.LiveVehicle;
import dev.pti.api.transit.domain.RouteSummary;
import dev.pti.api.transit.domain.Stop;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/** Small builders of the transit records, so that a test states only what matters to it. */
public final class TransitData {

    private TransitData() {}

    public static RouteSummary route(String id, int type, @Nullable Integer sortOrder) {
        return new RouteSummary(id, id, "Route " + id, id, type, "0053A0", "FFFFFF", sortOrder, 600);
    }

    public static Stop stop(String id, String name, double lon, double lat) {
        return new Stop(id, id, name, lat, lon, 0, 1);
    }

    public static LiveVehicle vehicle(String id, String routeId, Instant eventTimestamp) {
        return new LiveVehicle(
                id,
                id,
                routeId,
                "trip-" + id,
                0,
                "Downtown",
                44.948121,
                -93.278004,
                358.0f,
                7.4f,
                "IN_TRANSIT_TO",
                "51420",
                14,
                "MANY_SEATS_AVAILABLE",
                eventTimestamp,
                95,
                eventTimestamp.plusSeconds(71));
    }

    /** A scheduled call on route 18, direction 0, with no history and no trip update. */
    public static ArrivalCandidate candidate(String tripId, Instant scheduled) {
        return new ArrivalCandidate(
                LocalDate.parse("2026-09-29"),
                tripId,
                5,
                "18",
                0,
                "Downtown Minneapolis",
                scheduled,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public static ArrivalCandidate withHistory(ArrivalCandidate base, String avg, int samples) {
        return new ArrivalCandidate(
                base.serviceDate(),
                base.tripId(),
                base.stopSequence(),
                base.routeId(),
                base.directionId(),
                base.headsign(),
                base.scheduled(),
                new BigDecimal(avg),
                samples,
                base.observed(),
                base.scheduleRelationship(),
                base.realtimeTime(),
                base.realtimeEventTimestamp());
    }

    public static ArrivalCandidate withTripUpdate(
            ArrivalCandidate base,
            boolean observed,
            String scheduleRelationship,
            @Nullable Instant realtimeTime,
            @Nullable Instant eventTimestamp) {
        return new ArrivalCandidate(
                base.serviceDate(),
                base.tripId(),
                base.stopSequence(),
                base.routeId(),
                base.directionId(),
                base.headsign(),
                base.scheduled(),
                base.avgDelaySeconds(),
                base.sampleCount(),
                observed,
                scheduleRelationship,
                realtimeTime,
                eventTimestamp);
    }
}
