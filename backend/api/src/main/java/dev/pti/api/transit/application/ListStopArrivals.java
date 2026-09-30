package dev.pti.api.transit.application;

import dev.pti.api.platform.application.RequireActiveFeed;
import dev.pti.api.platform.application.port.DataAsOfReader;
import dev.pti.api.platform.domain.ActiveFeed;
import dev.pti.api.platform.domain.ApiException.FieldError;
import dev.pti.api.platform.domain.AsOfKind;
import dev.pti.api.platform.domain.NotFoundException;
import dev.pti.api.platform.domain.ValidationException;
import dev.pti.api.platform.domain.WithAsOf;
import dev.pti.api.transit.application.port.ArrivalReader;
import dev.pti.api.transit.application.port.StopReader;
import dev.pti.api.transit.domain.Arrival;
import dev.pti.api.transit.domain.ArrivalCandidate;
import dev.pti.api.transit.domain.ArrivalSettings;
import dev.pti.api.transit.domain.Confidence;
import dev.pti.api.transit.domain.StopArrivals;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.tx.TransactionRunner;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code GET /stops/{stopId}/arrivals} (DOC-32 E-08): the next calls at a stop with a predicted time and a confidence,
 * by the formula of DOC-23 §7.4. The query returns every scheduled call of the window; this class drops the calls
 * that already happened or are skipped, predicts the rest from the historical delay (or from the live trip update when
 * that is switched on), and keeps the first {@code limit} by effective time.
 */
public final class ListStopArrivals {

    private final RequireActiveFeed requireActiveFeed;
    private final StopReader stops;
    private final ArrivalReader arrivals;
    private final DataAsOfReader asOf;
    private final BusinessClock clock;
    private final ArrivalSettings settings;
    private final TransactionRunner tx;

    public ListStopArrivals(
            RequireActiveFeed requireActiveFeed,
            StopReader stops,
            ArrivalReader arrivals,
            DataAsOfReader asOf,
            BusinessClock clock,
            ArrivalSettings settings,
            TransactionRunner tx) {
        this.requireActiveFeed = requireActiveFeed;
        this.stops = stops;
        this.arrivals = arrivals;
        this.asOf = asOf;
        this.clock = clock;
        this.settings = settings;
        this.tx = tx;
    }

    /**
     * @param limit {@code 1..30}; the configured default when {@code null}
     * @param horizon {@code PT15M..PT3H}; the configured default when {@code null}
     * @throws ValidationException when {@code limit} or {@code horizon} is out of range
     * @throws NotFoundException when the ACTIVE feed has no such stop
     */
    public WithAsOf<StopArrivals> execute(String stopId, @Nullable Integer limit, @Nullable Duration horizon) {
        int effectiveLimit = limit != null ? limit : settings.defaultLimit();
        Duration effectiveHorizon = horizon != null ? horizon : settings.defaultHorizon();
        check(effectiveLimit, effectiveHorizon);
        ActiveFeed feed = requireActiveFeed.execute();
        Instant now = clock.instant();
        List<ArrivalCandidate> candidates = tx.inTransaction(() -> {
            stops.find(feed, stopId)
                    .orElseThrow(() -> new NotFoundException("The stop does not exist in the active feed."));
            return arrivals.candidates(feed, stopId, now, effectiveHorizon);
        });
        StopArrivals result =
                new StopArrivals(stopId, now, settings.realtimeEnabled(), upcoming(candidates, now, effectiveLimit));
        AsOfKind kind = settings.realtimeEnabled() ? AsOfKind.TRIP_UPDATE : AsOfKind.ETA_PREDICTION;
        return WithAsOf.of(result, asOf.asOf(kind));
    }

    private static void check(int limit, Duration horizon) {
        List<FieldError> errors = new ArrayList<>();
        if (limit < ArrivalSettings.MIN_LIMIT || limit > ArrivalSettings.MAX_LIMIT) {
            errors.add(new FieldError(
                    "limit", "must be between " + ArrivalSettings.MIN_LIMIT + " and " + ArrivalSettings.MAX_LIMIT));
        }
        if (horizon.compareTo(ArrivalSettings.MIN_HORIZON) < 0 || horizon.compareTo(ArrivalSettings.MAX_HORIZON) > 0) {
            errors.add(new FieldError(
                    "horizon",
                    "must be between " + ArrivalSettings.MIN_HORIZON + " and " + ArrivalSettings.MAX_HORIZON));
        }
        if (!errors.isEmpty()) {
            throw new ValidationException("The request is not valid.", errors);
        }
    }

    private List<Arrival> upcoming(List<ArrivalCandidate> candidates, Instant now, int limit) {
        return candidates.stream()
                .filter(candidate -> !gone(candidate))
                .map(candidate -> arrival(candidate, now))
                .filter(arrival -> !effective(arrival).isBefore(now))
                .sorted(Comparator.comparing(ListStopArrivals::effective)
                        .thenComparing(Arrival::scheduledArrival)
                        .thenComparing(Arrival::tripId))
                .limit(limit)
                .toList();
    }

    /** The vehicle has already been at the stop, or will not call there. */
    private static boolean gone(ArrivalCandidate candidate) {
        return Boolean.TRUE.equals(candidate.observed()) || "SKIPPED".equals(candidate.scheduleRelationship());
    }

    private Arrival arrival(ArrivalCandidate candidate, Instant now) {
        Integer sampleCount = candidate.sampleCount();
        BigDecimal average = candidate.avgDelaySeconds();
        int samples = sampleCount != null ? sampleCount : 0;
        long delay = average != null ? Math.round(average.doubleValue()) : 0;
        Instant predicted = candidate.scheduled().plusSeconds(delay);
        return new Arrival(
                candidate.tripId(),
                candidate.routeId(),
                candidate.directionId(),
                candidate.headsign(),
                candidate.serviceDate(),
                candidate.scheduled(),
                predicted,
                (int) delay,
                samples,
                Confidence.of(samples, settings.confidence()),
                realtime(candidate, now));
    }

    private @Nullable Instant realtime(ArrivalCandidate candidate, Instant now) {
        Instant time = candidate.realtimeTime();
        Instant reportedAt = candidate.realtimeEventTimestamp();
        if (!settings.realtimeEnabled() || time == null || reportedAt == null) {
            return null;
        }
        boolean fresh = !reportedAt.isBefore(now.minus(settings.realtimeMaxAge()));
        return fresh ? time : null;
    }

    private static Instant effective(Arrival arrival) {
        return arrival.realtimeArrival() != null ? arrival.realtimeArrival() : arrival.predictedArrival();
    }
}
