package dev.pti.api.transit.application;

import dev.pti.api.platform.application.RequireActiveFeed;
import dev.pti.api.platform.application.port.DataAsOfReader;
import dev.pti.api.platform.domain.ActiveFeed;
import dev.pti.api.platform.domain.AsOfKind;
import dev.pti.api.platform.domain.Caller;
import dev.pti.api.platform.domain.WithAsOf;
import dev.pti.api.transit.application.port.LiveVehicleReader;
import dev.pti.api.transit.application.port.OpenBunchingReader;
import dev.pti.api.transit.domain.BunchingOverlay;
import dev.pti.api.transit.domain.BunchingRole;
import dev.pti.api.transit.domain.LiveVehicle;
import dev.pti.api.transit.domain.LiveVehicles;
import dev.pti.api.transit.domain.OpenBunching;
import dev.pti.api.transit.domain.VehicleView;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.tx.TransactionRunner;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code GET /vehicles/live} (DOC-32 E-05): the newest position of every vehicle that reported in the last {@code
 * max-age}, for the first paint of the map and for polling when the event stream is down. A viewer also sees which
 * vehicles belong to an open bunching episode; an anonymous caller never does, because those episodes are for
 * operations.
 */
public final class ListLiveVehicles {

    private static final Logger log = LoggerFactory.getLogger(ListLiveVehicles.class);

    private static final Comparator<OpenBunching> CLOSEST_FIRST =
            Comparator.comparingInt(OpenBunching::gapSeconds).thenComparing(OpenBunching::episodeId);

    private final RequireActiveFeed requireActiveFeed;
    private final LiveVehicleReader vehicles;
    private final OpenBunchingReader bunching;
    private final DataAsOfReader asOf;
    private final BusinessClock clock;
    private final Duration maxAge;
    private final int maxItems;
    private final TransactionRunner tx;

    public ListLiveVehicles(
            RequireActiveFeed requireActiveFeed,
            LiveVehicleReader vehicles,
            OpenBunchingReader bunching,
            DataAsOfReader asOf,
            BusinessClock clock,
            Duration maxAge,
            int maxItems,
            TransactionRunner tx) {
        this.requireActiveFeed = requireActiveFeed;
        this.vehicles = vehicles;
        this.bunching = bunching;
        this.asOf = asOf;
        this.clock = clock;
        this.maxAge = maxAge;
        this.maxItems = maxItems;
        this.tx = tx;
    }

    /**
     * @param routeIds the routes to keep; every route when empty. A route that does not exist has no vehicles.
     * @return at most {@code max-items} vehicles in ascending vehicle id order
     */
    public WithAsOf<LiveVehicles> execute(Caller caller, Set<String> routeIds) {
        ActiveFeed feed = requireActiveFeed.execute();
        Instant now = clock.instant();
        List<LiveVehicle> snapshot =
                tx.inTransaction(() -> vehicles.snapshot(feed, routeIds, now, maxAge, maxItems + 1));
        if (snapshot.size() > maxItems) {
            log.warn("live vehicles truncated to {}", maxItems);
            snapshot = snapshot.subList(0, maxItems);
        }
        Map<String, BunchingOverlay> overlays = caller.isViewer() ? overlays() : Map.of();
        List<VehicleView> views = new ArrayList<>();
        for (LiveVehicle vehicle : snapshot) {
            views.add(new VehicleView(vehicle, overlays.get(vehicle.vehicleId())));
        }
        views.sort(Comparator.comparing(view -> view.vehicle().vehicleId()));
        return WithAsOf.of(new LiveVehicles(now, views), asOf.asOf(AsOfKind.VEHICLE_POSITION));
    }

    /** One overlay per vehicle: of the episodes it is in, the one with the smaller gap. */
    private Map<String, BunchingOverlay> overlays() {
        List<OpenBunching> open = tx.inTransaction(bunching::findOpen).stream()
                .sorted(CLOSEST_FIRST.reversed())
                .toList();
        Map<String, BunchingOverlay> byVehicle = new HashMap<>();
        // Closest last, so that it overwrites the looser episodes of the same vehicle.
        for (OpenBunching episode : open) {
            byVehicle.put(
                    episode.leaderVehicleId(), overlay(episode, BunchingRole.LEADER, episode.followerVehicleId()));
            byVehicle.put(
                    episode.followerVehicleId(), overlay(episode, BunchingRole.FOLLOWER, episode.leaderVehicleId()));
        }
        return byVehicle;
    }

    private static BunchingOverlay overlay(OpenBunching episode, BunchingRole role, String partner) {
        return new BunchingOverlay(episode.episodeId(), role, partner, episode.gapSeconds(), episode.headwaySeconds());
    }
}
