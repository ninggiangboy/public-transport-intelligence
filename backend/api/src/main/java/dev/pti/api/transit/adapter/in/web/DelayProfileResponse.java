package dev.pti.api.transit.adapter.in.web;

import dev.pti.api.transit.domain.DelayProfile;
import dev.pti.api.transit.domain.DelayProfileStop;
import dev.pti.api.transit.domain.EtaRow;
import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Response of {@code GET /routes/{routeId}/delay-profile} (DOC-32 E-04). The window and {@code computedAt} are absent
 * when no stop has history; a stop without history has no delay members.
 */
public record DelayProfileResponse(
        String routeId,
        int directionId,
        int dayOfWeek,
        int hourOfDay,
        @Nullable String windowStart,
        @Nullable String windowEnd,
        @Nullable String computedAt,
        List<DelayProfileItemResponse> items) {

    /** One stop of the direction, in pattern order. */
    public record DelayProfileItemResponse(
            String stopId,
            String name,
            int stopSequence,
            @Nullable BigDecimal avgDelaySeconds,
            @Nullable Integer medianDelaySeconds,
            @Nullable Integer p90DelaySeconds,
            int sampleCount,
            String confidence) {

        static DelayProfileItemResponse from(DelayProfileStop stop) {
            EtaRow eta = stop.eta();
            return new DelayProfileItemResponse(
                    stop.stopId(),
                    stop.name(),
                    stop.stopSequence(),
                    eta != null ? eta.avgDelaySeconds() : null,
                    eta != null ? eta.medianDelaySeconds() : null,
                    eta != null ? eta.p90DelaySeconds() : null,
                    stop.sampleCount(),
                    stop.confidence().name());
        }
    }

    static DelayProfileResponse from(DelayProfile profile) {
        return new DelayProfileResponse(
                profile.routeId(),
                profile.directionId(),
                profile.dayOfWeek(),
                profile.hourOfDay(),
                TransitParams.date(profile.windowStart()),
                TransitParams.date(profile.windowEnd()),
                TransitParams.instant(profile.computedAt()),
                profile.stops().stream().map(DelayProfileItemResponse::from).toList());
    }
}
