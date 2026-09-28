package dev.pti.simulator.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.common.gtfs.GtfsFormatException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FeedLoaderTest {

    @Test
    void loadsTheMiniFeed() {
        Feed feed = Feeds.mini();

        assertThat(feed.zone()).isEqualTo(ZoneId.of("America/Chicago"));
        assertThat(feed.trips()).hasSize(42);
        assertThat(feed.trips().values().stream()
                        .mapToInt(TripSchedule::stopCount)
                        .sum())
                .isEqualTo(1813);
        assertThat(feed.routes()).containsOnlyKeys("18", "901");
        assertThat(feed.route("901").orElseThrow().isRail()).isTrue();
        assertThat(feed.route("18").orElseThrow().displayName()).isEqualTo("18");
        assertThat(feed.route("901").orElseThrow().displayName()).isEqualTo("METRO Blue Line");
        assertThat(feed.vehicleIds()).hasSize(60).isSorted();
        assertThat(feed.validFrom()).isEqualTo(LocalDate.of(2026, 9, 26));
        assertThat(feed.validTo()).isEqualTo(LocalDate.of(2026, 11, 13));
    }

    @Test
    void ordersStopTimesAndKeepsDistancesNonDecreasing() {
        for (TripSchedule trip : Feeds.mini().trips().values()) {
            for (int i = 1; i < trip.stopCount(); i++) {
                assertThat(trip.stopSequence(i)).isGreaterThan(trip.stopSequence(i - 1));
                assertThat(trip.arrival(i)).isGreaterThanOrEqualTo(trip.departure(i - 1));
                assertThat(trip.dist(i)).isGreaterThanOrEqualTo(trip.dist(i - 1));
            }
            assertThat(trip.lastArrival()).isGreaterThan(trip.firstDeparture());
        }
    }

    /** DOC-25 §5.5: shape_dist_traveled is in metres. A stop sits within a few metres of its point on the shape. */
    @Test
    void distancesAreMetres() {
        TripSchedule trip = Feeds.mini().trips().values().stream()
                .filter(t -> t.routeId().equals("18"))
                .findFirst()
                .orElseThrow();

        for (int i = 0; i < trip.stopCount(); i++) {
            Shape.Point point = trip.shape().pointAt(trip.dist(i));
            assertThat(Geo.distance(
                            point.lat(),
                            point.lon(),
                            trip.stop(i).lat(),
                            trip.stop(i).lon()))
                    .as("stop %s of trip %s", trip.stop(i).id(), trip.tripId())
                    .isLessThan(60);
        }
    }

    @Test
    void checksTheFeedHash() {
        Path zip = Feeds.miniZip();

        assertThatThrownBy(() -> FeedLoader.load(zip, "00"))
                .isInstanceOf(GtfsFormatException.class)
                .hasMessageContaining("expected 00");
        assertThat(FeedLoader.load(zip, "").trips()).hasSize(42);
    }

    @Test
    void failsWithoutTheFile(@TempDir Path dir) {
        assertThatThrownBy(() -> FeedLoader.load(dir.resolve("missing.zip"), null))
                .isInstanceOf(GtfsFormatException.class)
                .hasMessageStartingWith("GTFS feed not found");
    }

    @Test
    void interpolatesMissingTimesByStopIndex() {
        int[] arr = {100, -1, -1, 400};
        int[] dep = {130, -1, -1, 400};

        FeedLoader.interpolateTimes("t", arr, dep);

        assertThat(arr).containsExactly(100, 220, 310, 400);
        assertThat(dep).containsExactly(130, 220, 310, 400);
        assertThatThrownBy(() -> FeedLoader.interpolateTimes("t", new int[] {-1, 5}, new int[] {-1, 5}))
                .isInstanceOf(GtfsFormatException.class);
    }

    /** DOC-25 §5.5: without shape_dist_traveled, stops are projected onto the polyline. */
    @Test
    void projectsStopsWhenDistancesAreMissing() {
        Shape shape = new Shape("s", new double[] {45.0, 45.0, 45.01}, new double[] {-93.0, -92.99, -92.99}, null);
        Stop[] stops = {
            new Stop("a", "A", 45.0001, -93.0), new Stop("b", "B", 45.0, -92.995), new Stop("c", "C", 45.01, -92.9901)
        };
        double[] dist = {Double.NaN, Double.NaN, Double.NaN};

        FeedLoader.fillDistances(shape, stops, dist);

        double firstLeg = Geo.distance(45.0, -93.0, 45.0, -92.99);
        assertThat(dist[0]).isCloseTo(0, org.assertj.core.data.Offset.offset(1.0));
        assertThat(dist[1]).isCloseTo(firstLeg / 2, org.assertj.core.data.Offset.offset(1.0));
        assertThat(dist[2]).isCloseTo(shape.length(), org.assertj.core.data.Offset.offset(1.0));
    }

    @Test
    void readsTheRealFeed() {
        Feed feed = Feeds.real();

        assertThat(feed.sha256()).isEqualTo(Feeds.REAL_SHA256);
        assertThat(feed.trips()).hasSize(20_220);
        assertThat(feed.trips().values().stream()
                        .mapToInt(TripSchedule::stopCount)
                        .sum())
                .isEqualTo(872_717);
        assertThat(feed.routes()).hasSize(127);
        assertThat(feed.vehicleIds()).hasSize(1233);
        assertThat(feed.validFrom()).isEqualTo(LocalDate.of(2026, 9, 26));
        assertThat(feed.validTo()).isEqualTo(LocalDate.of(2026, 11, 13));
    }
}
