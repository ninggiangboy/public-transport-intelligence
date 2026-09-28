package dev.pti.simulator.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import org.junit.jupiter.api.Test;

class ShapeTest {

    // An L: 1000 m east, then 1000 m north.
    private static final double[] LAT = {45.0, 45.0, 45.0 + 1000 / 111_195.0};
    private static final double[] LON = {-93.0, -93.0 + 1000 / (111_195.0 * Math.cos(Math.toRadians(45))), 0};

    static {
        LON[2] = LON[1];
    }

    private final Shape shape = new Shape("L", LAT, LON, new double[] {0, 1000, 2000});

    @Test
    void interpolatesAlongTheSegmentAndReportsItsBearing() {
        Shape.Point east = shape.pointAt(500);
        Shape.Point north = shape.pointAt(1500);

        assertThat(east.lat()).isCloseTo(45.0, offset(1e-9));
        assertThat(east.lon()).isCloseTo((LON[0] + LON[1]) / 2, offset(1e-9));
        assertThat(east.bearing()).isCloseTo(90, offset(0.1));
        assertThat(north.bearing()).isCloseTo(0, offset(0.1));
    }

    @Test
    void clampsToTheEnds() {
        assertThat(shape.pointAt(-10).lon()).isEqualTo(LON[0]);
        assertThat(shape.pointAt(5000).lat()).isEqualTo(LAT[2]);
        assertThat(shape.pointAt(2000).bearing()).isCloseTo(0, offset(0.1));
        assertThat(shape.length()).isEqualTo(2000);
    }

    @Test
    void computesDistancesWhenTheFeedHasNone() {
        Shape computed = new Shape("L", LAT, LON, new double[] {0, Double.NaN, 2000});

        assertThat(computed.length()).isCloseTo(2000, offset(2.0));
    }

    @Test
    void handlesRepeatedPointsAndSinglePoints() {
        Shape repeated =
                new Shape("r", new double[] {45, 45, 45.01}, new double[] {-93, -93, -93}, new double[] {0, 0, 1112});
        Shape single = new Shape("p", new double[] {45}, new double[] {-93}, null);

        assertThat(repeated.pointAt(0).bearing()).isCloseTo(0, offset(0.1));
        assertThat(single.pointAt(100).lat()).isEqualTo(45);
    }

    @Test
    void buildsAStraightShapeThroughStops() {
        Shape through = Shape.through("t", new Stop[] {new Stop("a", "A", 45, -93), new Stop("b", "B", 45.01, -93)});

        assertThat(through.length()).isCloseTo(1112, offset(1.0));
    }
}
