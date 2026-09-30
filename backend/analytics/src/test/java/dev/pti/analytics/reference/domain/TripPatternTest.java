package dev.pti.analytics.reference.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.data.Offset.offset;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code TripPattern} and {@code RouteInfo} (DOC-23 §3). */
class TripPatternTest {

    private static StopTime stop(int sequence, String id, Double shapeDist, double lat, double lon) {
        return new StopTime(sequence, id, sequence * 60, sequence * 60 + 10, shapeDist, lat, lon);
    }

    @Test
    void distanceIsShapeDistanceWhenEveryStopHasIt() {
        TripPattern pattern = TripPattern.fromStopTimes(
                "t1",
                "18",
                0,
                List.of(
                        stop(1, "a", 0.0, 44.0, -93.0),
                        stop(2, "b", 1500.0, 44.01, -93.0),
                        stop(5, "c", 4000.0, 44.03, -93.0)));

        assertThat(pattern.stops()).extracting(PatternStop::dist).containsExactly(0.0, 1500.0, 4000.0);
    }

    @Test
    void distanceIsCumulativeHaversineWhenAStopLacksShapeDistance() {
        TripPattern pattern = TripPattern.fromStopTimes(
                "t1", "18", 0, List.of(stop(1, "a", 0.0, 44.0, -93.0), stop(2, "b", null, 44.01, -93.0)));

        // 0.01 degrees of latitude is about 1112 m.
        assertThat(pattern.stops().get(0).dist()).isZero();
        assertThat(pattern.stops().get(1).dist()).isCloseTo(1111.95, offset(1.0));
    }

    @Test
    void theStopIndexIsLookedUpBySequence() {
        TripPattern pattern = TripPattern.fromStopTimes(
                "t1",
                "18",
                1,
                List.of(
                        stop(1, "a", 0.0, 44.0, -93.0),
                        stop(2, "b", 10.0, 44.0, -93.0),
                        stop(5, "c", 20.0, 44.0, -93.0)));

        assertThat(pattern.indexOfSequence(1)).isZero();
        assertThat(pattern.indexOfSequence(5)).isEqualTo(2);
        assertThat(pattern.indexOfSequence(3)).isEqualTo(-1);
        assertThat(pattern.indexOfSequence(99)).isEqualTo(-1);
        assertThat(pattern.directionId()).isEqualTo(1);
    }

    @Test
    void stopsMustBeInIncreasingSequence() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> TripPattern.fromStopTimes(
                        "t1", "18", 0, List.of(stop(2, "a", 0.0, 44.0, -93.0), stop(1, "b", 1.0, 44.0, -93.0))));
    }

    @Test
    void aDirectionWithoutALabelIsNamedByItsNumber() {
        RouteInfo route = new RouteInfo("18", 3, "18", Map.of(0, "Northbound"));

        assertThat(route.directionLabel(0)).isEqualTo("Northbound");
        assertThat(route.directionLabel(1)).isEqualTo("Direction 1");
    }
}
