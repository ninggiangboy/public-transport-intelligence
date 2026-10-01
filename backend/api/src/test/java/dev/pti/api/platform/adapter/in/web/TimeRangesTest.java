package dev.pti.api.platform.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.api.platform.domain.ValidationException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** How {@code from}, {@code to} and {@code since} are read (DOC-31 §4.1…§4.3, AG-05, AG-06, AG-07). */
class TimeRangesTest {

    private static final Instant NOW = Instant.parse("2026-09-29T21:19:35.400Z");
    private static final Duration WEEK = Duration.ofDays(7);
    private static final Duration DAY = Duration.ofHours(24);

    private final TimeRanges ranges = new TimeRanges(() -> NOW, Duration.ofDays(31));

    private static void assertInvalid(Runnable call, String field) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        ValidationException.class,
                        e -> assertThat(e.errors())
                                .extracting(error -> error.field())
                                .contains(field));
    }

    @Test
    @DisplayName("Without parameters the range is the default span up to now, cut to the minute")
    void defaults() {
        TimeRanges.Range range = ranges.resolve(null, null, WEEK);

        assertThat(range.to()).isEqualTo(Instant.parse("2026-09-29T21:19:00Z"));
        assertThat(range.from()).isEqualTo(Instant.parse("2026-09-22T21:19:00Z"));
    }

    @Test
    @DisplayName("A list of the newest rows goes up to the next minute, so that nothing up to now is cut off")
    void throughNow() {
        TimeRanges.Range range = ranges.resolveThroughNow(null, null, DAY);

        assertThat(range.to()).isEqualTo(Instant.parse("2026-09-29T21:20:00Z"));
        assertThat(range.from()).isEqualTo(Instant.parse("2026-09-28T21:20:00Z"));
        assertThat(ranges.resolveThroughNow("-60m", null, DAY).to()).isEqualTo(Instant.parse("2026-09-29T21:20:00Z"));
    }

    @Test
    @DisplayName("The next minute as a default does not turn a range of exactly 31 days into one that is too long")
    void throughNowKeepsTheLimit() {
        assertThat(ranges.resolveThroughNow("-31d", null, DAY).from()).isEqualTo(NOW.minus(Duration.ofDays(31)));
        assertInvalid(() -> ranges.resolveThroughNow("-32d", null, DAY), "from");
    }

    @Test
    @DisplayName("AG-06 relative times count back from now on the axis of the instance: -60m, -6h, -7d")
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

    @Test
    @DisplayName("A point in time (since) is read like from, and may be at most 31 days old and a day ahead")
    void points() {
        assertThat(ranges.point("since", "-5m")).isEqualTo(NOW.minus(Duration.ofMinutes(5)));
        assertThat(ranges.point("since", "2026-09-29T16:00:00-05:00")).isEqualTo(Instant.parse("2026-09-29T21:00:00Z"));
        assertInvalid(() -> ranges.point("since", "2026-09-29T10:00:00"), "since");
        assertInvalid(() -> ranges.point("since", "-32d"), "since");
        assertInvalid(() -> ranges.point("since", "2026-10-01T22:00:00Z"), "since");
    }

    @Test
    @DisplayName("Two instances are two axes: event time follows the business clock, audit time the real one")
    void axes() {
        Instant business = NOW.minus(Duration.ofHours(12));
        TimeRanges eventTime = new TimeRanges(() -> business, Duration.ofDays(31));

        assertThat(eventTime.resolve("-60m", null, DAY).from()).isEqualTo(business.minus(Duration.ofMinutes(60)));
        assertThat(ranges.resolve("-60m", null, DAY).from()).isEqualTo(NOW.minus(Duration.ofMinutes(60)));
        assertThat(eventTime.now()).isEqualTo(business);
    }
}
