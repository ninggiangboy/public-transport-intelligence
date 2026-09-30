package dev.pti.api.transit.application;

import dev.pti.api.platform.application.RequireActiveFeed;
import dev.pti.api.platform.application.port.DataAsOfReader;
import dev.pti.api.platform.domain.ActiveFeed;
import dev.pti.api.platform.domain.AsOfKind;
import dev.pti.api.platform.domain.NotFoundException;
import dev.pti.api.platform.domain.WithAsOf;
import dev.pti.api.transit.application.port.EtaProfileReader;
import dev.pti.api.transit.application.port.RouteDetailReader;
import dev.pti.api.transit.domain.Confidence;
import dev.pti.api.transit.domain.ConfidenceThresholds;
import dev.pti.api.transit.domain.DelayProfile;
import dev.pti.api.transit.domain.DelayProfileStop;
import dev.pti.api.transit.domain.DirectionPattern;
import dev.pti.api.transit.domain.EtaRow;
import dev.pti.api.transit.domain.PatternStop;
import dev.pti.api.transit.domain.RouteDetail;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.tx.TransactionRunner;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * {@code GET /routes/{routeId}/delay-profile} (DOC-32 E-04): the historical delay at each stop of a direction for one
 * weekday and hour, listed in the order of the stops of the route pattern (E-02).
 */
public final class GetRouteDelayProfile {

    private final RequireActiveFeed requireActiveFeed;
    private final RouteDetailReader details;
    private final EtaProfileReader eta;
    private final DataAsOfReader asOf;
    private final BusinessClock clock;
    private final ConfidenceThresholds thresholds;
    private final TransactionRunner tx;

    public GetRouteDelayProfile(
            RequireActiveFeed requireActiveFeed,
            RouteDetailReader details,
            EtaProfileReader eta,
            DataAsOfReader asOf,
            BusinessClock clock,
            ConfidenceThresholds thresholds,
            TransactionRunner tx) {
        this.requireActiveFeed = requireActiveFeed;
        this.details = details;
        this.eta = eta;
        this.asOf = asOf;
        this.clock = clock;
        this.thresholds = thresholds;
        this.tx = tx;
    }

    /**
     * @param dayOfWeek ISO 1-7 in the timezone of the feed; the weekday of business now when {@code null}
     * @param hourOfDay 0-23 in the timezone of the feed; the hour of business now when {@code null}
     * @throws NotFoundException when the route, or its direction, does not exist
     */
    public WithAsOf<DelayProfile> execute(
            String routeId, int directionId, @Nullable Integer dayOfWeek, @Nullable Integer hourOfDay) {
        ActiveFeed feed = requireActiveFeed.execute();
        ZonedDateTime local = clock.instant().atZone(feed.timezone());
        int day = dayOfWeek != null ? dayOfWeek : local.getDayOfWeek().getValue();
        int hour = hourOfDay != null ? hourOfDay : local.getHour();
        DelayProfile profile = tx.inTransaction(() -> {
            RouteDetail detail = details.find(feed, routeId)
                    .orElseThrow(() -> new NotFoundException("The route does not exist in the active feed."));
            DirectionPattern direction = detail.direction(directionId)
                    .orElseThrow(() -> new NotFoundException("The route has no such direction."));
            return profile(routeId, direction, day, hour, eta.read(routeId, day, hour));
        });
        return WithAsOf.of(profile, asOf.asOf(AsOfKind.ETA_PREDICTION));
    }

    private DelayProfile profile(
            String routeId, DirectionPattern direction, int dayOfWeek, int hourOfDay, List<EtaRow> rows) {
        Map<String, EtaRow> byStop = new HashMap<>();
        rows.forEach(row -> byStop.put(row.stopId(), row));
        List<DelayProfileStop> stops = new ArrayList<>();
        for (PatternStop stop : direction.stops()) {
            EtaRow row = byStop.get(stop.stopId());
            int samples = row != null ? row.sampleCount() : 0;
            stops.add(new DelayProfileStop(
                    stop.stopId(), stop.name(), stop.stopSequence(), row, Confidence.of(samples, thresholds)));
        }
        EtaRow latest = rows.stream()
                .max(java.util.Comparator.comparing(EtaRow::computedAt))
                .orElse(null);
        return new DelayProfile(
                routeId,
                direction.directionId(),
                dayOfWeek,
                hourOfDay,
                latest != null ? latest.windowStart() : null,
                latest != null ? latest.windowEnd() : null,
                latest != null ? latest.computedAt() : null,
                stops);
    }
}
