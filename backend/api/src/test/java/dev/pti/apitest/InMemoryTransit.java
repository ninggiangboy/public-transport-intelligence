package dev.pti.apitest;

import dev.pti.api.platform.application.port.ActiveFeedReader;
import dev.pti.api.platform.domain.ActiveFeed;
import dev.pti.api.transit.application.port.ArrivalReader;
import dev.pti.api.transit.application.port.EtaProfileReader;
import dev.pti.api.transit.application.port.LiveVehicleReader;
import dev.pti.api.transit.application.port.OpenBunchingReader;
import dev.pti.api.transit.application.port.RouteCatalogReader;
import dev.pti.api.transit.application.port.RouteDelayReader;
import dev.pti.api.transit.application.port.RouteDetailReader;
import dev.pti.api.transit.application.port.StopDisruptionReader;
import dev.pti.api.transit.application.port.StopReader;
import dev.pti.api.transit.application.port.StopRoutesReader;
import dev.pti.api.transit.domain.ArrivalCandidate;
import dev.pti.api.transit.domain.BoundingBox;
import dev.pti.api.transit.domain.DelayBucket;
import dev.pti.api.transit.domain.EtaRow;
import dev.pti.api.transit.domain.LiveVehicle;
import dev.pti.api.transit.domain.OpenBunching;
import dev.pti.api.transit.domain.RouteCatalog;
import dev.pti.api.transit.domain.RouteDetail;
import dev.pti.api.transit.domain.RouteSummary;
import dev.pti.api.transit.domain.Stop;
import dev.pti.api.transit.domain.StopDisruption;
import dev.pti.api.transit.domain.StopRoutes;
import dev.pti.common.events.Audience;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The ports of the transit feature and the ACTIVE feed, held in memory. Use cases and controllers run against them
 * without a database; a test puts what it needs into the public fields and reads back what the use case asked for.
 * Two ports of the feature have the same method shape ({@code read(feed)}, {@code find(feed, id)}), so each port is a
 * field rather than one class implementing all of them. Call {@link #reset()} before each test that shares an
 * instance.
 */
public final class InMemoryTransit {

    public static final ActiveFeed FEED = new ActiveFeed(
            3,
            "2026-08-23",
            ZoneId.of("America/Chicago"),
            LocalDate.parse("2026-08-23"),
            LocalDate.parse("2026-12-12"),
            Instant.parse("2026-09-27T08:34:40Z"));

    public @Nullable ActiveFeed feed = FEED;
    public List<RouteSummary> routes = new ArrayList<>();
    public Map<String, RouteDetail> details = new HashMap<>();
    public List<DelayBucket> delayBuckets = new ArrayList<>();
    public List<EtaRow> etaRows = new ArrayList<>();
    public List<LiveVehicle> vehicles = new ArrayList<>();
    public List<OpenBunching> bunching = new ArrayList<>();
    public Map<String, Stop> stops = new LinkedHashMap<>();
    public StopRoutes stopRoutes = new StopRoutes(Map.of());
    public List<StopDisruption> disruptions = new ArrayList<>();
    public List<ArrivalCandidate> candidates = new ArrayList<>();

    public RouteDelayReader.@Nullable Request lastDelayRequest;
    public @Nullable Set<String> lastVehicleRoutes;
    public int lastVehicleLimit;
    public @Nullable Set<Audience> lastAudiences;
    public @Nullable Collection<String> lastDisruptionRoutes;
    public @Nullable Duration lastArrivalHorizon;
    public @Nullable Instant lastArrivalNow;
    public @Nullable String lastEtaKey;
    public int bunchingReads;

    public final ActiveFeedReader activeFeed = () -> Optional.ofNullable(feed);

    public final RouteCatalogReader routeCatalog = active -> new RouteCatalog(active.feedVersionId(), routes);

    public final RouteDetailReader routeDetail = (active, routeId) -> Optional.ofNullable(details.get(routeId));

    public final RouteDelayReader routeDelays = request -> {
        lastDelayRequest = request;
        return delayBuckets;
    };

    public final EtaProfileReader etaProfile = (routeId, dayOfWeek, hourOfDay) -> {
        lastEtaKey = routeId + ":" + dayOfWeek + ":" + hourOfDay;
        return etaRows;
    };

    public final LiveVehicleReader liveVehicles = (active, routeIds, now, maxAge, limit) -> {
        lastVehicleRoutes = routeIds;
        lastVehicleLimit = limit;
        Instant oldest = now.minus(maxAge);
        return vehicles.stream()
                .filter(vehicle -> routeIds.isEmpty() || routeIds.contains(vehicle.routeId()))
                .filter(vehicle -> !vehicle.eventTimestamp().isBefore(oldest))
                .sorted(Comparator.comparing(LiveVehicle::eventTimestamp)
                        .reversed()
                        .thenComparing(LiveVehicle::vehicleId))
                .limit(limit)
                .toList();
    };

    public final OpenBunchingReader openBunching = () -> {
        bunchingReads++;
        return bunching;
    };

    public final StopReader stopReader = new StopReader() {
        @Override
        public Optional<Stop> find(ActiveFeed active, String stopId) {
            return Optional.ofNullable(stops.get(stopId));
        }

        @Override
        public List<Stop> searchText(ActiveFeed active, String q, int limit) {
            String needle = q.toLowerCase(Locale.ROOT);
            return stops.values().stream()
                    .filter(stop -> q.equals(stop.code()) || lower(stop).contains(needle))
                    .sorted(Comparator.comparingInt((Stop stop) -> rank(stop, q, needle))
                            .thenComparing(Stop::name)
                            .thenComparing(Stop::stopId))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<Stop> searchArea(ActiveFeed active, AreaQuery query) {
            BoundingBox box = query.bbox();
            List<String> routeStops = query.routeStopIds();
            return stops.values().stream()
                    .filter(stop -> box == null || inside(box, stop))
                    .filter(stop -> routeStops == null || routeStops.contains(stop.stopId()))
                    .filter(stop -> query.afterStopId() == null || stop.stopId().compareTo(query.afterStopId()) > 0)
                    .sorted(Comparator.comparing(Stop::stopId))
                    .limit(query.fetchSize())
                    .toList();
        }
    };

    public final StopRoutesReader stopRoutesReader = active -> stopRoutes;

    public final StopDisruptionReader stopDisruptions = (routeIds, audiences) -> {
        lastAudiences = audiences;
        lastDisruptionRoutes = routeIds;
        return disruptions;
    };

    public final ArrivalReader arrivals = (active, stopId, now, horizon) -> {
        lastArrivalNow = now;
        lastArrivalHorizon = horizon;
        return candidates;
    };

    public void reset() {
        feed = FEED;
        routes = new ArrayList<>();
        details = new HashMap<>();
        delayBuckets = new ArrayList<>();
        etaRows = new ArrayList<>();
        vehicles = new ArrayList<>();
        bunching = new ArrayList<>();
        stops = new LinkedHashMap<>();
        stopRoutes = new StopRoutes(Map.of());
        disruptions = new ArrayList<>();
        candidates = new ArrayList<>();
        lastDelayRequest = null;
        lastVehicleRoutes = null;
        lastVehicleLimit = 0;
        lastAudiences = null;
        lastDisruptionRoutes = null;
        lastArrivalHorizon = null;
        lastArrivalNow = null;
        lastEtaKey = null;
        bunchingReads = 0;
    }

    private static String lower(Stop stop) {
        return stop.name().toLowerCase(Locale.ROOT);
    }

    private static int rank(Stop stop, String q, String needle) {
        if (q.equals(stop.code())) {
            return 0;
        }
        return lower(stop).startsWith(needle) ? 1 : 2;
    }

    private static boolean inside(BoundingBox box, Stop stop) {
        return stop.lon() >= box.minLon()
                && stop.lon() <= box.maxLon()
                && stop.lat() >= box.minLat()
                && stop.lat() <= box.maxLat();
    }
}
