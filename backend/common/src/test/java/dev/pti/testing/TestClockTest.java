package dev.pti.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TestClockTest {

    @Test
    void movesOnlyWhenTold() {
        TestClock clock = TestClock.atDefault();

        assertThat(clock.instant()).isEqualTo(TestClock.DEFAULT);
        assertThat(clock.advance(Duration.ofSeconds(5)).instant()).isEqualTo(TestClock.DEFAULT.plusSeconds(5));
        assertThat(clock.set(Instant.EPOCH).realNow()).isEqualTo(Instant.EPOCH);
    }
}
