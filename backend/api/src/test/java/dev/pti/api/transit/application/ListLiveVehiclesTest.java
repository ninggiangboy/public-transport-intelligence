package dev.pti.api.transit.application;

import static dev.pti.apitest.TransitData.vehicle;
import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.api.platform.application.RequireActiveFeed;
import dev.pti.api.platform.domain.AsOfKind;
import dev.pti.api.platform.domain.Caller;
import dev.pti.api.platform.domain.Role;
import dev.pti.api.platform.domain.ServiceUnavailableException;
import dev.pti.api.platform.domain.WithAsOf;
import dev.pti.api.transit.domain.BunchingOverlay;
import dev.pti.api.transit.domain.BunchingRole;
import dev.pti.api.transit.domain.LiveVehicles;
import dev.pti.api.transit.domain.OpenBunching;
import dev.pti.api.transit.domain.VehicleView;
import dev.pti.apitest.DirectTransactions;
import dev.pti.apitest.InMemoryTransit;
import dev.pti.testing.TestClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@code GET /vehicles/live} (DOC-32 E-05): freshness, the bunching overlay by role, ordering and the hard cap. */
class ListLiveVehiclesTest {

    private static final Instant NOW = Instant.parse("2026-09-29T21:19:35Z");
    private static final Caller VIEWER = new Caller("viewer", "Viewer", Set.of(Role.VIEWER), null);

    private final InMemoryTransit transit = new InMemoryTransit();
    private final List<AsOfKind> asked = new ArrayList<>();

    private ListLiveVehicles useCase(int maxItems) {
        return new ListLiveVehicles(
                new RequireActiveFeed(transit.activeFeed),
                transit.liveVehicles,
                transit.openBunching,
                kind -> {
                    asked.add(kind);
                    return Optional.of(NOW);
                },
                TestClock.at(NOW),
                Duration.ofMinutes(5),
                maxItems,
                new DirectTransactions());
    }

    private static UUID episode(int n) {
        return UUID.fromString("00000000-0000-5000-8000-00000000000" + n);
    }

    @Test
    @DisplayName("EP-05 a vehicle whose last position is older than 5 minutes is not in the result")
    void oldPositionsLeaveTheMap() {
        transit.vehicles.add(vehicle("1", "18", NOW.minusSeconds(299)));
        transit.vehicles.add(vehicle("2", "18", NOW.minusSeconds(301)));

        WithAsOf<LiveVehicles> result = useCase(1500).execute(Caller.anonymous(), Set.of());

        assertThat(result.value().vehicles())
                .extracting(view -> view.vehicle().vehicleId())
                .containsExactly("1");
        assertThat(result.value().businessNow()).isEqualTo(NOW);
        assertThat(result.asOf()).isEqualTo(NOW);
        assertThat(asked).containsExactly(AsOfKind.VEHICLE_POSITION);
    }

    @Test
    @DisplayName("The vehicles come back in ascending vehicle id order, whatever order the query gave them in")
    void orderedByVehicleId() {
        transit.vehicles.add(vehicle("1203", "18", NOW.minusSeconds(1)));
        transit.vehicles.add(vehicle("1187", "18", NOW.minusSeconds(9)));
        transit.vehicles.add(vehicle("1190", "18", NOW.minusSeconds(5)));

        List<VehicleView> views =
                useCase(1500).execute(Caller.anonymous(), Set.of()).value().vehicles();

        assertThat(views).extracting(view -> view.vehicle().vehicleId()).containsExactly("1187", "1190", "1203");
    }

    @Test
    @DisplayName("The routes asked for reach the query; none means every route")
    void routeFilterIsPassedOn() {
        useCase(1500).execute(Caller.anonymous(), Set.of("18", "901"));

        assertThat(transit.lastVehicleRoutes).containsExactlyInAnyOrder("18", "901");
    }

    @Test
    @DisplayName("EP-06 anonymous never gets the overlay and the bunching episodes are not even read")
    void anonymousHasNoOverlay() {
        transit.vehicles.add(vehicle("1187", "18", NOW));
        transit.vehicles.add(vehicle("1203", "18", NOW));
        transit.bunching.add(new OpenBunching(episode(1), "1187", "1203", 112, 600));

        List<VehicleView> views =
                useCase(1500).execute(Caller.anonymous(), Set.of()).value().vehicles();

        assertThat(views).allSatisfy(view -> assertThat(view.bunching()).isNull());
        assertThat(transit.bunchingReads).isZero();
    }

    @Test
    @DisplayName("EP-06 a viewer sees the overlay on both the leader and the follower, each naming the other")
    void viewerSeesBothSides() {
        transit.vehicles.add(vehicle("1187", "18", NOW));
        transit.vehicles.add(vehicle("1203", "18", NOW));
        transit.vehicles.add(vehicle("1999", "18", NOW));
        transit.bunching.add(new OpenBunching(episode(1), "1187", "1203", 112, 600));

        List<VehicleView> views =
                useCase(1500).execute(VIEWER, Set.of()).value().vehicles();

        assertThat(views.get(0).bunching())
                .isEqualTo(new BunchingOverlay(episode(1), BunchingRole.LEADER, "1203", 112, 600));
        assertThat(views.get(1).bunching())
                .isEqualTo(new BunchingOverlay(episode(1), BunchingRole.FOLLOWER, "1187", 112, 600));
        assertThat(views.get(2).bunching()).isNull();
    }

    @Test
    @DisplayName("A vehicle in two episodes gets the one with the smaller gap")
    void closestEpisodeWins() {
        transit.vehicles.add(vehicle("A", "18", NOW));
        transit.vehicles.add(vehicle("B", "18", NOW));
        transit.vehicles.add(vehicle("C", "18", NOW));
        // B follows A with a gap of 200 s and leads C with a gap of 90 s.
        transit.bunching.add(new OpenBunching(episode(1), "A", "B", 200, 600));
        transit.bunching.add(new OpenBunching(episode(2), "B", "C", 90, 600));

        List<VehicleView> views =
                useCase(1500).execute(VIEWER, Set.of()).value().vehicles();

        assertThat(views.get(1).bunching().episodeId()).isEqualTo(episode(2));
        assertThat(views.get(1).bunching().role()).isEqualTo(BunchingRole.LEADER);
        assertThat(views.get(0).bunching().episodeId()).isEqualTo(episode(1));
        assertThat(views.get(2).bunching().episodeId()).isEqualTo(episode(2));
    }

    @Test
    @DisplayName("More than max-items vehicles are cut to max-items, the most recently reported kept")
    void cappedAtMaxItems() {
        for (int i = 0; i < 6; i++) {
            transit.vehicles.add(vehicle("v" + i, "18", NOW.minusSeconds(i)));
        }

        List<VehicleView> views =
                useCase(4).execute(Caller.anonymous(), Set.of()).value().vehicles();

        assertThat(views).hasSize(4);
        assertThat(views).extracting(view -> view.vehicle().vehicleId()).containsExactly("v0", "v1", "v2", "v3");
        assertThat(transit.lastVehicleLimit).isEqualTo(5);
    }

    @Test
    @DisplayName("Without an ACTIVE feed the snapshot is a 503")
    void noFeed() {
        transit.feed = null;

        Assertions.assertThatThrownBy(() -> useCase(1500).execute(Caller.anonymous(), Set.of()))
                .isInstanceOf(ServiceUnavailableException.class);
    }
}
