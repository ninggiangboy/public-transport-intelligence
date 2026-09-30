package dev.pti.analytics.core.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** The lock names of DOC-23 §2.5. */
class LockNamesTest {

    @Test
    void namesFollowTheTable() {
        assertThat(LockNames.bunching("18")).isEqualTo("pti:analytics:bunching:18");
        assertThat(LockNames.disruption("18")).isEqualTo("pti:analytics:disruption:18");
        assertThat(LockNames.eta()).isEqualTo("pti:analytics:eta");
        assertThat(LockNames.otp(LocalDate.parse("2026-09-29"))).isEqualTo("pti:analytics:otp:2026-09-29");
        assertThat(LockNames.ticketing(Instant.parse("2026-09-29T21:15:00Z")))
                .isEqualTo("pti:analytics:ticketing:2026-09-29T21:15:00Z");
    }
}
