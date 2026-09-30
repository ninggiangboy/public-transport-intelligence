package dev.pti.api.transit.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.api.platform.domain.ValidationException;
import dev.pti.api.transit.domain.BoundingBox;
import dev.pti.api.transit.domain.BucketSize;
import dev.pti.testing.TestClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** How {@code from}, {@code to} and the other query parameters of the transit endpoints are read (DOC-31 §4, §6). */
class TimeRangesTest {

    private static final Instant NOW = Instant.parse("2026-09-29T21:19:35.400Z");
    private static final Duration WEEK = Duration.ofDays(7);

    private final TimeRanges ranges = new TimeRanges(TestClock.at(NOW), Duration.ofDays(31));

    private static void assertInvalid(Runnable call, String field) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        ValidationException.class,
                        e -> assertThat(e.errors())
                                .extracting(error -> error.field())
                                .contains(field));
    }

    @Test
    @DisplayName("Without parameters the range is the default span up to business now, cut to the minute")
    void defaults() {
        TimeRanges.Range range = ranges.resolve(null, null, WEEK);

        assertThat(range.to()).isEqualTo(Instant.parse("2026-09-29T21:19:00Z"));
        assertThat(range.from()).isEqualTo(Instant.parse("2026-09-22T21:19:00Z"));
    }

    @Test
    @DisplayName("Relative times count back from business now: -60m, -6h, -7d")
    void relativeTimes() {
        assertThat(ranges.resolve("-60m", null, WEEK).from()).isEqualTo(NOW.minus(Duration.ofMinutes(60)));
        assertThat(ranges.resolve("-6h", "-1h", WEEK).from()).isEqualTo(NOW.minus(Duration.ofHours(6)));
        assertThat(ranges.resolve("-6h", "-1h", WEEK).to()).isEqualTo(NOW.minus(Duration.ofHours(1)));
        assertThat(ranges.resolve("-7d", null, WEEK).from()).isEqualTo(NOW.minus(WEEK));
    }

    @Test
    @DisplayName("An ISO time with an offset is converted to UTC; from defaults to a span before an explicit to")
    void isoTimes() {
        TimeRanges.Range range = ranges.resolve(null, "2026-09-29T16:00:00-05:00", WEEK);

        assertThat(range.to()).isEqualTo(Instant.parse("2026-09-29T21:00:00Z"));
        assertThat(range.from()).isEqualTo(Instant.parse("2026-09-22T21:00:00Z"));
    }

    @Test
    @DisplayName("AG-05 a time without an offset, or that is not a time, is an error on its field")
    void badTimes() {
        assertInvalid(() -> ranges.resolve("2026-09-29T10:00:00", null, WEEK), "from");
        assertInvalid(() -> ranges.resolve(null, "yesterday", WEEK), "to");
        assertInvalid(() -> ranges.resolve("-5", null, WEEK), "from");
        assertInvalid(() -> ranges.resolve("+5m", null, WEEK), "from");
    }

    @Test
    @DisplayName("AG-07 from must be before to, the range is at most 31 days, to at most a day ahead")
    void limits() {
        assertInvalid(() -> ranges.resolve("-1h", "-1h", WEEK), "from");
        assertInvalid(() -> ranges.resolve("-32d", null, WEEK), "from");
        assertThat(ranges.resolve("-31d", "-0m", WEEK).from()).isEqualTo(NOW.minus(Duration.ofDays(31)));
        assertInvalid(() -> ranges.resolve("-1h", "2026-09-30T22:00:00Z", WEEK), "to");
        assertThat(ranges.resolve("-1h", "2026-09-30T21:00:00Z", WEEK).to())
                .isEqualTo(Instant.parse("2026-09-30T21:00:00Z"));
    }

    @Test
    @DisplayName("Both bad fields are reported together")
    void bothFields() {
        assertThatThrownBy(() -> ranges.resolve("x", "y", WEEK))
                .isInstanceOfSatisfying(
                        ValidationException.class,
                        e -> assertThat(e.errors())
                                .extracting(error -> error.field())
                                .containsExactly("to", "from"));
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
