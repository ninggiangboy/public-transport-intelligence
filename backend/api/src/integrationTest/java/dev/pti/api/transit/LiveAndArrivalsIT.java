package dev.pti.api.transit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.api.platform.application.RequireActiveFeed;
import dev.pti.api.platform.domain.Caller;
import dev.pti.api.platform.domain.Role;
import dev.pti.api.transit.adapter.out.jdbc.JdbcArrivalReader;
import dev.pti.api.transit.adapter.out.jdbc.JdbcLiveVehicleReader;
import dev.pti.api.transit.adapter.out.jdbc.JdbcOpenBunchingReader;
import dev.pti.api.transit.adapter.out.jdbc.JdbcStopReader;
import dev.pti.api.transit.application.ListLiveVehicles;
import dev.pti.api.transit.application.ListStopArrivals;
import dev.pti.api.transit.domain.Arrival;
import dev.pti.api.transit.domain.ArrivalCandidate;
import dev.pti.api.transit.domain.ArrivalSettings;
import dev.pti.api.transit.domain.ConfidenceThresholds;
import dev.pti.api.transit.domain.LiveVehicle;
import dev.pti.api.transit.domain.OpenBunching;
import dev.pti.api.transit.domain.VehicleView;
import dev.pti.common.tx.TransactionRunner;
import dev.pti.testing.TestClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * E-05 and E-08 against the real warehouse, at a fixed business time: Tuesday 2026-09-29 16:20 in Chicago (21:20Z).
 * Vehicle positions and trip updates are placed around it; the arrivals cases are those of DOC-23 §18.4 (AN-A) that
 * depend on the SQL: the service dates, GTFS times past midnight, pickup type, and what the warehouse already saw.
 */
class LiveAndArrivalsIT extends TransitIntegrationSupport {

    private static final Instant NOW = Instant.parse("2026-09-29T21:20:00Z");
    private static final ConfidenceThresholds CONFIDENCE = new ConfidenceThresholds(10, 30);

    @Autowired
    private JdbcLiveVehicleReader vehicles;

    @Autowired
    private JdbcOpenBunchingReader bunching;

    @Autowired
    private JdbcArrivalReader arrivals;

    @Autowired
    private JdbcStopReader stopReader;

    @Autowired
    private RequireActiveFeed requireActiveFeed;

    @Autowired
    @Qualifier("readerTx")
    private TransactionRunner readerTx;

    // ------------------------------------------------------------------------------------------------ E-05

    private void vehicleFacts() {
        installNetwork();
        partitions("2026-09-28", "2026-09-30");
        asOwner(
                "INSERT INTO dw.dim_vehicle (vehicle_id, vehicle_label, source) VALUES ('1203', 'Bus 1203', 'REALTIME')",
                """
                INSERT INTO dw.vehicle_position_latest (vehicle_id, service_date, route_id, trip_id, direction_id, lat,
                  lon, bearing, speed_mps, current_stop_sequence, stop_id, current_status, occupancy_status,
                  event_timestamp, batch_id)
                VALUES
                  ('1203', DATE '2026-09-29', '18', 'b2', 0, 44.948121, -93.278004, 358, 7.4, 2, 's2', 'IN_TRANSIT_TO',
                   'MANY_SEATS_AVAILABLE', TIMESTAMPTZ '2026-09-29 21:19:30Z', gen_random_uuid()),
                  ('1187', DATE '2026-09-29', '18', 'b3', 0, 44.93, -93.278, NULL, NULL, 1, 's1', 'STOPPED_AT',
                   NULL, TIMESTAMPTZ '2026-09-29 21:19:50Z', gen_random_uuid()),
                  ('3000', DATE '2026-09-29', '901', 'r1', 0, 44.9, -93.2, 90, 12, 1, 's6', 'INCOMING_AT',
                   NULL, TIMESTAMPTZ '2026-09-29 21:19:59Z', gen_random_uuid()),
                  ('4000', DATE '2026-09-29', '18', 'not-in-the-feed', 1, 44.95, -93.27, NULL, NULL, 4, 's4',
                   'IN_TRANSIT_TO', NULL, TIMESTAMPTZ '2026-09-29 21:15:00Z', gen_random_uuid()),
                  ('9999', DATE '2026-09-29', '18', 'b1', 0, 44.92, -93.27, NULL, NULL, 1, 's1', 'STOPPED_AT',
                   NULL, TIMESTAMPTZ '2026-09-29 21:14:59Z', gen_random_uuid())""",
                """
                INSERT INTO dw.fact_trip_update (service_date, trip_id, stop_sequence, route_id, direction_id, stop_id,
                  vehicle_id, schedule_relationship, scheduled_arrival, arrival_time, delay_seconds, is_observed,
                  event_timestamp, payload_hash, batch_id)
                VALUES (DATE '2026-09-29', 'b2', 2, '18', 0, 's2', '1203', 'SCHEDULED',
                  TIMESTAMPTZ '2026-09-29 21:19:06Z', TIMESTAMPTZ '2026-09-29 21:20:41Z', 95, false,
                  TIMESTAMPTZ '2026-09-29 21:19:30Z', repeat('0', 64), gen_random_uuid())""");
    }

    private List<LiveVehicle> snapshot(Set<String> routes, int limit) {
        return vehicles.snapshot(activeFeed(), routes, NOW, Duration.ofMinutes(5), limit);
    }

    @Test
    @DisplayName("EP-05 a vehicle that last reported 5 minutes and a second ago is not in the snapshot; newest first")
    void freshVehiclesOnly() {
        vehicleFacts();

        List<LiveVehicle> snapshot = snapshot(Set.of(), 1501);

        assertThat(snapshot).extracting(LiveVehicle::vehicleId).containsExactly("3000", "1187", "1203", "4000");
    }

    @Test
    @DisplayName("E-05 the members of a vehicle: label, trip headsign, and the delay and arrival at its current stop")
    void vehicleMembers() {
        vehicleFacts();

        LiveVehicle bus = snapshot(Set.of("18"), 10).stream()
                .filter(vehicle -> vehicle.vehicleId().equals("1203"))
                .findFirst()
                .orElseThrow();

        assertThat(bus.label()).isEqualTo("Bus 1203");
        assertThat(bus.routeId()).isEqualTo("18");
        assertThat(bus.tripId()).isEqualTo("b2");
        assertThat(bus.directionId()).isZero();
        assertThat(bus.headsign()).isEqualTo("Downtown");
        assertThat(bus.lat()).isEqualTo(44.948121);
        assertThat(bus.lon()).isEqualTo(-93.278004);
        assertThat(bus.bearing()).isEqualTo(358.0f);
        assertThat(bus.speedMps()).isEqualTo(7.4f);
        assertThat(bus.currentStatus()).isEqualTo("IN_TRANSIT_TO");
        assertThat(bus.stopId()).isEqualTo("s2");
        assertThat(bus.currentStopSequence()).isEqualTo(2);
        assertThat(bus.occupancyStatus()).isEqualTo("MANY_SEATS_AVAILABLE");
        assertThat(bus.eventTimestamp()).isEqualTo(Instant.parse("2026-09-29T21:19:30Z"));
        assertThat(bus.delaySeconds()).isEqualTo(95);
        assertThat(bus.stopArrivalAt()).isEqualTo(Instant.parse("2026-09-29T21:20:41Z"));
    }

    @Test
    @DisplayName(
            "E-05 a vehicle without a trip update or a label, on a trip that is not in the feed, has those members empty")
    void vehicleWithoutExtras() {
        vehicleFacts();

        LiveVehicle lost = snapshot(Set.of(), 10).stream()
                .filter(vehicle -> vehicle.vehicleId().equals("4000"))
                .findFirst()
                .orElseThrow();
        LiveVehicle plain = snapshot(Set.of(), 10).stream()
                .filter(vehicle -> vehicle.vehicleId().equals("1187"))
                .findFirst()
                .orElseThrow();

        assertThat(lost.headsign()).isNull();
        assertThat(lost.label()).isNull();
        assertThat(lost.delaySeconds()).isNull();
        assertThat(lost.stopArrivalAt()).isNull();
        assertThat(plain.bearing()).isNull();
        assertThat(plain.speedMps()).isNull();
        assertThat(plain.occupancyStatus()).isNull();
        assertThat(plain.delaySeconds()).isNull();
        assertThat(plain.headsign()).isEqualTo("Downtown");
    }

    @Test
    @DisplayName("E-05 the route filter, one route or several, and a route that does not exist has no vehicles")
    void routeFilter() {
        vehicleFacts();

        assertThat(snapshot(Set.of("901"), 10))
                .extracting(LiveVehicle::vehicleId)
                .containsExactly("3000");
        assertThat(snapshot(Set.of("901", "18"), 10)).hasSize(4);
        assertThat(snapshot(Set.of("nope"), 10)).isEmpty();
    }

    @Test
    @DisplayName("E-05 the limit keeps the most recently reported vehicles")
    void limit() {
        vehicleFacts();

        assertThat(snapshot(Set.of(), 2)).extracting(LiveVehicle::vehicleId).containsExactly("3000", "1187");
    }

    @Test
    @DisplayName("E-05 the bunching episodes that are open, and only those")
    void openBunching() {
        installNetwork();
        asOwner("""
                INSERT INTO insight.insight_bus_bunching (id, route_id, direction_id, vehicle_leader, vehicle_follower,
                  trip_leader, trip_follower, episode_start, episode_end, status, close_reason,
                  scheduled_headway_seconds, threshold_seconds, min_gap_seconds, last_gap_seconds, last_evaluated_at,
                  batch_id)
                VALUES
                  ('6f1c2a9e-4b1d-5c8e-9a2f-3d4e5f6a7b8c', '18', 0, '1187', '1203', 'b1', 'b2',
                   TIMESTAMPTZ '2026-09-29 21:10:00Z', NULL, 'OPEN', NULL, 600, 300, 100, 112,
                   TIMESTAMPTZ '2026-09-29 21:19:45Z', gen_random_uuid()),
                  ('7f1c2a9e-4b1d-5c8e-9a2f-3d4e5f6a7b8c', '18', 0, '1100', '1101', 'b3', 'b4',
                   TIMESTAMPTZ '2026-09-29 20:00:00Z', TIMESTAMPTZ '2026-09-29 20:30:00Z', 'CLOSED', 'GAP_RECOVERED',
                   600, 300, 100, 500, TIMESTAMPTZ '2026-09-29 20:30:00Z', gen_random_uuid())""");

        List<OpenBunching> open = bunching.findOpen();

        assertThat(open).hasSize(1);
        assertThat(open.get(0).episodeId().toString()).isEqualTo("6f1c2a9e-4b1d-5c8e-9a2f-3d4e5f6a7b8c");
        assertThat(open.get(0).leaderVehicleId()).isEqualTo("1187");
        assertThat(open.get(0).followerVehicleId()).isEqualTo("1203");
        assertThat(open.get(0).gapSeconds()).isEqualTo(112);
        assertThat(open.get(0).headwaySeconds()).isEqualTo(600);
    }

    @Test
    @DisplayName("EP-06 through the database: an anonymous caller gets no overlay, a viewer gets it on both vehicles")
    void overlayThroughTheDatabase() {
        vehicleFacts();
        asOwner("""
                INSERT INTO insight.insight_bus_bunching (id, route_id, direction_id, vehicle_leader, vehicle_follower,
                  trip_leader, trip_follower, episode_start, status, scheduled_headway_seconds, threshold_seconds,
                  min_gap_seconds, last_gap_seconds, last_evaluated_at, batch_id)
                VALUES ('6f1c2a9e-4b1d-5c8e-9a2f-3d4e5f6a7b8c', '18', 0, '1187', '1203', 'b3', 'b2',
                  TIMESTAMPTZ '2026-09-29 21:10:00Z', 'OPEN', 600, 300, 100, 112,
                  TIMESTAMPTZ '2026-09-29 21:19:45Z', gen_random_uuid())""");
        ListLiveVehicles useCase = new ListLiveVehicles(
                requireActiveFeed,
                vehicles,
                bunching,
                kind -> Optional.empty(),
                TestClock.at(NOW),
                Duration.ofMinutes(5),
                1500,
                readerTx);
        Caller viewer = new Caller("viewer", "Viewer", Set.of(Role.VIEWER), null);

        List<VehicleView> anonymous =
                useCase.execute(Caller.anonymous(), Set.of()).value().vehicles();
        List<VehicleView> signedIn = useCase.execute(viewer, Set.of()).value().vehicles();

        assertThat(anonymous).extracting(VehicleView::bunching).containsOnlyNulls();
        assertThat(signedIn)
                .extracting(view -> view.vehicle().vehicleId())
                .containsExactly("1187", "1203", "3000", "4000");
        assertThat(signedIn.get(0).bunching().role().name()).isEqualTo("LEADER");
        assertThat(signedIn.get(1).bunching().role().name()).isEqualTo("FOLLOWER");
        assertThat(signedIn.get(1).bunching().partnerVehicleId()).isEqualTo("1187");
        assertThat(signedIn.get(2).bunching()).isNull();
    }

    // ------------------------------------------------------------------------------------------------ E-08

    private static int gtfsSeconds(int hour, int minute) {
        return hour * 3600 + minute * 60;
    }

    private void arrivalFacts() {
        installNetwork();
        partitions("2026-09-28", "2026-09-30");
        asOwnerInFeed("""
                INSERT INTO dw.gtfs_trip (feed_version_id, trip_id, route_id, service_id, direction_id, trip_headsign)
                SELECT {fv}, t, '18', 'wk', 0, 'Downtown Minneapolis'
                FROM unnest(ARRAY['e1','e2','e3','e4','e5','e6','e7','e8']) AS t""", """
                INSERT INTO dw.gtfs_stop_time (feed_version_id, trip_id, stop_sequence, stop_id, arrival_seconds,
                  departure_seconds, pickup_type)
                VALUES
                  ({fv}, 'e1', 1, 's2', %d, %d, 0),
                  ({fv}, 'e2', 1, 's2', %d, %d, 0),
                  ({fv}, 'e3', 1, 's2', %d, %d, 0),
                  ({fv}, 'e4', 1, 's2', %d, %d, 0),
                  ({fv}, 'e5', 1, 's2', %d, %d, 1),
                  ({fv}, 'e6', 1, 's2', %d, %d, 0),
                  ({fv}, 'e7', 1, 's2', %d, %d, 0),
                  ({fv}, 'e8', 1, 's2', 91800, 91800, 0)""".formatted(
                        gtfsSeconds(16, 10), gtfsSeconds(16, 10),
                        gtfsSeconds(16, 24), gtfsSeconds(16, 24),
                        gtfsSeconds(17, 5), gtfsSeconds(17, 5),
                        gtfsSeconds(16, 28), gtfsSeconds(16, 28),
                        gtfsSeconds(16, 30), gtfsSeconds(16, 30),
                        gtfsSeconds(16, 40), gtfsSeconds(16, 40),
                        gtfsSeconds(16, 45), gtfsSeconds(16, 45)));
        asOwner(
                """
                INSERT INTO insight.insight_eta_prediction (route_id, stop_id, day_of_week, hour_of_day,
                  avg_delay_seconds, median_delay_seconds, p90_delay_seconds, sample_count, window_start, window_end,
                  computed_at, batch_id)
                VALUES ('18', 's2', 2, 16, 64.4, 51, 170, 36, DATE '2026-09-01', DATE '2026-09-28',
                  TIMESTAMPTZ '2026-09-29 21:05:12Z', gen_random_uuid())""",
                // e4 has been at the stop, e6 will not call there, e7 is on its way with a fresh prediction.
                """
                INSERT INTO dw.fact_trip_update (service_date, trip_id, stop_sequence, route_id, direction_id, stop_id,
                  vehicle_id, schedule_relationship, scheduled_arrival, arrival_time, delay_seconds, is_observed,
                  event_timestamp, payload_hash, batch_id)
                VALUES
                  (DATE '2026-09-29', 'e4', 1, '18', 0, 's2', 'v4', 'SCHEDULED', TIMESTAMPTZ '2026-09-29 21:28:00Z',
                   TIMESTAMPTZ '2026-09-29 21:18:00Z', -600, true, TIMESTAMPTZ '2026-09-29 21:18:00Z',
                   repeat('0', 64), gen_random_uuid()),
                  (DATE '2026-09-29', 'e6', 1, '18', 0, 's2', 'v6', 'SKIPPED', NULL, NULL, NULL, false,
                   TIMESTAMPTZ '2026-09-29 21:19:00Z', repeat('0', 64), gen_random_uuid()),
                  (DATE '2026-09-29', 'e7', 1, '18', 0, 's2', 'v7', 'SCHEDULED', TIMESTAMPTZ '2026-09-29 21:45:00Z',
                   TIMESTAMPTZ '2026-09-29 21:47:10Z', 130, false, TIMESTAMPTZ '2026-09-29 21:19:00Z',
                   repeat('0', 64), gen_random_uuid())""");
    }

    private ListStopArrivals arrivalsAt(Instant now, boolean realtime) {
        ArrivalSettings settings =
                new ArrivalSettings(10, Duration.ofMinutes(90), realtime, Duration.ofMinutes(2), CONFIDENCE);
        return new ListStopArrivals(
                requireActiveFeed,
                stopReader,
                arrivals,
                kind -> Optional.empty(),
                TestClock.at(now),
                settings,
                readerTx);
    }

    @Test
    @DisplayName("The query returns the calls of the window, not those without pickup, with the trip update state")
    void candidates() {
        arrivalFacts();

        List<ArrivalCandidate> found = arrivals.candidates(activeFeed(), "s2", NOW, Duration.ofMinutes(90));

        assertThat(found)
                .extracting(ArrivalCandidate::tripId)
                .containsExactlyInAnyOrder("e1", "e2", "e3", "e4", "e6", "e7");
        ArrivalCandidate e2 = find(found, "e2");
        assertThat(e2.serviceDate()).isEqualTo(LocalDate.parse("2026-09-29"));
        assertThat(e2.scheduled()).isEqualTo(Instant.parse("2026-09-29T21:24:00Z"));
        assertThat(e2.routeId()).isEqualTo("18");
        assertThat(e2.headsign()).isEqualTo("Downtown Minneapolis");
        assertThat(e2.avgDelaySeconds()).isEqualByComparingTo("64.4");
        assertThat(e2.sampleCount()).isEqualTo(36);
        assertThat(e2.observed()).isNull();
        assertThat(find(found, "e3").avgDelaySeconds()).isNull();
        assertThat(find(found, "e4").observed()).isTrue();
        assertThat(find(found, "e6").scheduleRelationship()).isEqualTo("SKIPPED");
        ArrivalCandidate e7 = find(found, "e7");
        assertThat(e7.observed()).isFalse();
        assertThat(e7.realtimeTime()).isEqualTo(Instant.parse("2026-09-29T21:47:10Z"));
        assertThat(e7.realtimeEventTimestamp()).isEqualTo(Instant.parse("2026-09-29T21:19:00Z"));
    }

    private static ArrivalCandidate find(List<ArrivalCandidate> candidates, String tripId) {
        return candidates.stream()
                .filter(candidate -> candidate.tripId().equals(tripId))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("EP-10 the upcoming calls: the past, the observed, the skipped and the no-pickup ones are gone")
    void upcomingCalls() {
        arrivalFacts();

        List<Arrival> list =
                arrivalsAt(NOW, false).execute("s2", null, null).value().arrivals();

        assertThat(list).extracting(Arrival::tripId).containsExactly("e2", "e7", "e3");
        assertThat(list.get(0).predictedArrival()).isEqualTo(Instant.parse("2026-09-29T21:25:04Z"));
        assertThat(list.get(0).predictedDelaySeconds()).isEqualTo(64);
        assertThat(list.get(0).confidence().name()).isEqualTo("HIGH");
        assertThat(list.get(1).predictedArrival()).isEqualTo(Instant.parse("2026-09-29T21:46:04Z"));
        assertThat(list.get(1).realtimeArrival()).isNull();
        assertThat(list.get(2).predictedArrival()).isEqualTo(Instant.parse("2026-09-29T22:05:00Z"));
        assertThat(list.get(2).confidence().name()).isEqualTo("NONE");
        assertThat(list.get(2).sampleCount()).isZero();
    }

    @Test
    @DisplayName("AN-A-05 realtime on: the fresh trip update of e7 gives its realtimeArrival")
    void realtime() {
        arrivalFacts();

        List<Arrival> list =
                arrivalsAt(NOW, true).execute("s2", null, null).value().arrivals();

        assertThat(list).extracting(Arrival::tripId).containsExactly("e2", "e7", "e3");
        assertThat(list.get(1).realtimeArrival()).isEqualTo(Instant.parse("2026-09-29T21:47:10Z"));
        assertThat(list.get(0).realtimeArrival()).isNull();
    }

    @Test
    @DisplayName("AN-A-06 realtime on, 3 minutes later the trip update of e7 is too old to show")
    void staleRealtime() {
        arrivalFacts();

        List<Arrival> list = arrivalsAt(NOW.plusSeconds(120), true)
                .execute("s2", null, null)
                .value()
                .arrivals();

        assertThat(list).extracting(Arrival::tripId).contains("e7");
        assertThat(arrivalOf(list, "e7").realtimeArrival()).isNull();
    }

    private static Arrival arrivalOf(List<Arrival> arrivals, String tripId) {
        return arrivals.stream()
                .filter(arrival -> arrival.tripId().equals(tripId))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("E-08 the horizon bounds the window and the limit the list")
    void horizonAndLimit() {
        arrivalFacts();

        assertThat(arrivalsAt(NOW, false)
                        .execute("s2", null, Duration.ofMinutes(15))
                        .value()
                        .arrivals())
                .extracting(Arrival::tripId)
                .containsExactly("e2");
        caches.cache("arrivals").invalidateAll();
        assertThat(arrivalsAt(NOW, false).execute("s2", 2, null).value().arrivals())
                .extracting(Arrival::tripId)
                .containsExactly("e2", "e7");
    }

    @Test
    @DisplayName("AN-A-09 a call at 25:30 of yesterday's service date is 01:30 today and is included")
    void pastMidnightOfTheServiceDay() {
        arrivalFacts();
        Instant shortlyAfterMidnight = Instant.parse("2026-09-29T06:20:00Z");

        List<Arrival> list = arrivalsAt(shortlyAfterMidnight, false)
                .execute("s2", null, null)
                .value()
                .arrivals();

        assertThat(list).extracting(Arrival::tripId).containsExactly("e8");
        assertThat(list.get(0).serviceDate()).isEqualTo(LocalDate.parse("2026-09-28"));
        assertThat(list.get(0).scheduledArrival()).isEqualTo(Instant.parse("2026-09-29T06:30:00Z"));
    }

    @Test
    @DisplayName("E-08 a stop with no calls in the window has an empty list")
    void noCalls() {
        arrivalFacts();

        assertThat(arrivalsAt(NOW, false).execute("s5", null, null).value().arrivals())
                .isEmpty();
    }
}
