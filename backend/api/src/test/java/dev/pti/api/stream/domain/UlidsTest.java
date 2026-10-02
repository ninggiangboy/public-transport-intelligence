package dev.pti.api.stream.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.api.testing.StreamFixtures;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class UlidsTest {

    @Test
    void theTimeOfAUlidIsItsFirstTenCharacters() {
        Instant at = Instant.parse("2026-09-29T21:19:31.020Z");

        assertThat(Ulids.time(StreamFixtures.ulid(at))).contains(at);
        assertThat(Ulids.time(StreamFixtures.ulid(at).toLowerCase(java.util.Locale.ROOT)))
                .contains(at);
    }

    @Test
    void anythingElseHasNoTime() {
        assertThat(Ulids.time("not-a-ulid")).isEmpty();
        assertThat(Ulids.time("01J8ZK3V5Q7X2M4N6P8R0T2V4U")).isEmpty(); // U is not Crockford base32
        assertThat(Ulids.time("81J8ZK3V5Q7X2M4N6P8R0T2V4W")).isEmpty(); // beyond 48 bits
    }
}
