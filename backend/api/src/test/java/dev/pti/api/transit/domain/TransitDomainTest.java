package dev.pti.api.transit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The pure parts of the transit feature: confidence, shape simplification, bounding boxes and the stop index. */
class TransitDomainTest {

    private static final ConfidenceThresholds THRESHOLDS = new ConfidenceThresholds(10, 30);

    @Test
    @DisplayName("AN-E-07 sample count 0 / 9 / 10 / 29 / 30 is NONE / LOW / MEDIUM / MEDIUM / HIGH")
    void confidenceLevels() {
        assertThat(Confidence.of(0, THRESHOLDS)).isEqualTo(Confidence.NONE);
        assertThat(Confidence.of(9, THRESHOLDS)).isEqualTo(Confidence.LOW);
        assertThat(Confidence.of(10, THRESHOLDS)).isEqualTo(Confidence.MEDIUM);
        assertThat(Confidence.of(29, THRESHOLDS)).isEqualTo(Confidence.MEDIUM);
        assertThat(Confidence.of(30, THRESHOLDS)).isEqualTo(Confidence.HIGH);
    }

    @Test
    @DisplayName("Thresholds need 1 <= medium < high")
    void thresholdsGuard() {
        assertThatThrownBy(() -> new ConfidenceThresholds(0, 30)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConfidenceThresholds(30, 30)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Simplification drops points within 2 m of the line and keeps the corners and the ends")
    void simplifiesAStraightLine() {
        List<GeoPoint> points = new ArrayList<>();
        for (int i = 0; i <= 100; i++) {
            // 1 m of wobble on a line 0.001 degrees apart per point, well under the 2 m tolerance.
            points.add(new GeoPoint(-93.0 + i * 0.001, 44.0 + (i % 2) * 0.000005));
        }
        points.add(new GeoPoint(-92.9, 44.01));

        List<GeoPoint> simplified = LineSimplifier.simplify(points, LineSimplifier.TOLERANCE_DEGREES);

        assertThat(simplified).hasSize(3);
        assertThat(simplified.get(0)).isEqualTo(new GeoPoint(-93.0, 44.0));
        assertThat(simplified.get(simplified.size() - 1)).isEqualTo(new GeoPoint(-92.9, 44.01));
    }

    @Test
    @DisplayName("Coordinates are rounded to six decimals, and a short line is returned as it is")
    void roundsCoordinates() {
        List<GeoPoint> simplified =
                LineSimplifier.simplify(List.of(new GeoPoint(-93.27812345678, 44.9234111111)), 0.00002);

        assertThat(simplified).containsExactly(new GeoPoint(-93.278123, 44.923411));
    }

    @Test
    @DisplayName("A shape of several thousand points does not overflow the stack")
    void longShape() {
        List<GeoPoint> points = new ArrayList<>();
        for (int i = 0; i < 50_000; i++) {
            points.add(new GeoPoint(-93.0 + i * 0.00001, 44.0 + Math.sin(i / 50.0) * 0.01));
        }

        assertThat(LineSimplifier.simplify(points, LineSimplifier.TOLERANCE_DEGREES))
                .hasSizeLessThan(points.size());
    }

    @Test
    @DisplayName("A bounding box needs the minimum below the maximum; the area is in square degrees")
    void boundingBox() {
        assertThat(new BoundingBox(-93.5, 44.5, -93.0, 45.0).area()).isEqualTo(0.25);
        assertThatThrownBy(() -> new BoundingBox(-93.0, 44.5, -93.0, 45.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("The stop index answers both ways: routes of a stop and stops of a route")
    void stopRoutes() {
        StopRoutes index = new StopRoutes(Map.of(
                "a", List.of(new StopRouteRef("18", List.of("Downtown")), new StopRouteRef("5", List.of())),
                "b", List.of(new StopRouteRef("18", List.of()))));

        assertThat(index.routeIdsOf("a")).containsExactly("18", "5");
        assertThat(index.stopsOf("18")).containsExactlyInAnyOrder("a", "b");
        assertThat(index.stopsOf("5")).containsExactly("a");
        assertThat(index.routeIdsOf("zzz")).isEmpty();
        assertThat(index.stopsOf("zzz")).isEmpty();
    }

    @Test
    @DisplayName("The catalog filters by route type and answers whether it has a route")
    void catalog() {
        RouteCatalog catalog = new RouteCatalog(
                3,
                List.of(
                        new RouteSummary("901", null, "Blue", "Blue", 0, null, null, 1, null),
                        new RouteSummary("18", "18", null, "18", 3, null, null, 24, 600)));

        assertThat(catalog.ofTypes(java.util.Set.of(3)).routes()).hasSize(1);
        assertThat(catalog.ofTypes(java.util.Set.of())).isSameAs(catalog);
        assertThat(catalog.contains("18")).isTrue();
        assertThat(catalog.contains("x")).isFalse();
    }
}
