package dev.pti.api.transit.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.api.platform.domain.ValidationException;
import dev.pti.api.transit.domain.BoundingBox;
import dev.pti.api.transit.domain.BucketSize;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** How the query parameters of the transit endpoints are read (DOC-31 §6, DOC-32 §3). */
class TransitParamsTest {

    private static void assertInvalid(Runnable call, String field) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        ValidationException.class,
                        e -> assertThat(e.errors())
                                .extracting(error -> error.field())
                                .contains(field));
    }

    // ------------------------------------------------------------------------------------------------ parameters

    @Test
    @DisplayName("bucket is hour by default, then hour, day or hour-of-week; any other spelling is an error")
    void buckets() {
        assertThat(TransitParams.bucket(null)).isEqualTo(BucketSize.HOUR);
        assertThat(TransitParams.bucket("day")).isEqualTo(BucketSize.DAY);
        assertThat(TransitParams.bucket("hour-of-week")).isEqualTo(BucketSize.HOUR_OF_WEEK);
        assertThat(TransitParams.wire(BucketSize.HOUR_OF_WEEK)).isEqualTo("hour-of-week");
        assertInvalid(() -> TransitParams.bucket("Hour"), "bucket");
        assertInvalid(() -> TransitParams.bucket("hour_of_week"), "bucket");
    }

    @Test
    @DisplayName("bbox is minLon,minLat,maxLon,maxLat with the minimum below the maximum and at most 0.25 deg2")
    void bbox() {
        BoundingBox box = TransitParams.bbox("-93.5, 44.5, -93.0, 45.0");

        assertThat(box).isEqualTo(new BoundingBox(-93.5, 44.5, -93.0, 45.0));
        for (String bad : new String[] {
            "",
            "1,2,3",
            "1,2,3,4,5",
            "a,1,2,3",
            "NaN,1,2,3",
            "-93,45,-94,44",
            "-94,44,-93,45",
            "-181,0,0,1",
            "0,-91,1,0"
        }) {
            assertInvalid(() -> TransitParams.bbox(bad), "bbox");
        }
    }

    @Test
    @DisplayName("A multi-valued parameter drops blanks and duplicates, and takes at most 20 values")
    void multiValued() {
        assertThat(TransitParams.values("routeId", java.util.List.of("b", " a ", "", "b")))
                .containsExactly("a", "b");
        assertThat(TransitParams.values("routeId", null)).isEmpty();
        java.util.List<String> many = java.util.stream.IntStream.range(0, 21)
                .mapToObj(Integer::toString)
                .toList();
        assertInvalid(() -> TransitParams.values("routeId", many), "routeId");
    }

    @Test
    @DisplayName("horizon is an ISO-8601 duration")
    void durations() {
        assertThat(TransitParams.duration("horizon", "PT90M")).isEqualTo(Duration.ofMinutes(90));
        assertInvalid(() -> TransitParams.duration("horizon", "90 minutes"), "horizon");
    }
}
