package dev.pti.common.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** DOC-25 T-02. */
class BusinessClockTest {

    private static final Instant REAL = Instant.parse("2026-10-02T07:10:00Z");

    @Test
    void shiftsRealTimeByTheOffset() {
        BusinessClock clock =
                new BusinessClock(Clock.fixed(REAL, ZoneId.of("Asia/Ho_Chi_Minh")), Duration.parse("PT14H30M"));

        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-10-02T21:40:00Z"));
        assertThat(clock.realNow()).isEqualTo(REAL);
        assertThat(clock.businessTimeAt(Instant.parse("2026-10-02T00:00:00Z")))
                .isEqualTo(Instant.parse("2026-10-02T14:30:00Z"));
        assertThat(clock.offset()).isEqualTo(Duration.ofMinutes(870));
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void acceptsNegativeAndLimitOffsets() {
        assertThat(new BusinessClock(Clock.fixed(REAL, ZoneOffset.UTC), Duration.ofHours(-24)).instant())
                .isEqualTo(REAL.minus(Duration.ofDays(1)));
        assertThat(new BusinessClock(Clock.fixed(REAL, ZoneOffset.UTC), Duration.ofHours(24)).instant())
                .isEqualTo(REAL.plus(Duration.ofDays(1)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT30S", "PT1M0.5S", "PT24H1M", "-PT25H"})
    void rejectsOffsetsThatAreNotWholeMinutesOrTooLarge(String offset) {
        assertThatThrownBy(() -> new BusinessClock(Clock.systemUTC(), Duration.parse(offset)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("pti.clock.offset must be");
    }

    @Test
    void refusesAZonedView() {
        BusinessClock clock = new BusinessClock(Clock.fixed(REAL, ZoneOffset.UTC), Duration.ZERO);

        assertThatThrownBy(() -> clock.withZone(ZoneId.of("America/Chicago")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
