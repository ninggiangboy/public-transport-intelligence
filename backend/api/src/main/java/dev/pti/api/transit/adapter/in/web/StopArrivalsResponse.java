package dev.pti.api.transit.adapter.in.web;

import dev.pti.api.platform.domain.ApiTime;
import dev.pti.api.transit.domain.Arrival;
import dev.pti.api.transit.domain.StopArrivals;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Response of {@code GET /stops/{stopId}/arrivals} (DOC-32 E-08). */
public record StopArrivalsResponse(
        String stopId, String businessNow, boolean realtimeEnabled, List<ArrivalResponse> items) {

    /** One upcoming call. {@code realtimeArrival} is only present when realtime is on and the trip update is fresh. */
    public record ArrivalResponse(
            String tripId,
            String routeId,
            int directionId,
            @Nullable String headsign,
            String serviceDate,
            String scheduledArrival,
            String predictedArrival,
            int predictedDelaySeconds,
            int sampleCount,
            String confidence,
            @Nullable String realtimeArrival) {

        static ArrivalResponse from(Arrival arrival) {
            return new ArrivalResponse(
                    arrival.tripId(),
                    arrival.routeId(),
                    arrival.directionId(),
                    arrival.headsign(),
                    arrival.serviceDate().toString(),
                    ApiTime.format(arrival.scheduledArrival()),
                    ApiTime.format(arrival.predictedArrival()),
                    arrival.predictedDelaySeconds(),
                    arrival.sampleCount(),
                    arrival.confidence().name(),
                    TransitParams.instant(arrival.realtimeArrival()));
        }
    }

    static StopArrivalsResponse from(StopArrivals arrivals) {
        return new StopArrivalsResponse(
                arrivals.stopId(),
                ApiTime.format(arrivals.businessNow()),
                arrivals.realtimeEnabled(),
                arrivals.arrivals().stream().map(ArrivalResponse::from).toList());
    }
}
