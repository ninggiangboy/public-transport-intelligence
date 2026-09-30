package dev.pti.api.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.api.platform.domain.ActiveFeed;
import dev.pti.api.platform.domain.ServiceUnavailableException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RequireActiveFeedTest {

    private static final ActiveFeed FEED = new ActiveFeed(
            3,
            "2026-08-23",
            ZoneId.of("America/Chicago"),
            LocalDate.parse("2026-08-23"),
            LocalDate.parse("2026-12-12"),
            Instant.parse("2026-09-27T08:34:40Z"));

    @Test
    void returnsTheActiveFeed() {
        assertThat(new RequireActiveFeed(() -> Optional.of(FEED)).execute()).isSameAs(FEED);
    }

    @Test
    @DisplayName("AG-19 without an ACTIVE feed the use case is a 503 that says to retry in 30 seconds")
    void failsWhenThereIsNoFeed() {
        assertThatThrownBy(() -> new RequireActiveFeed(Optional::empty).execute())
                .isInstanceOfSatisfying(ServiceUnavailableException.class, e -> {
                    assertThat(e.retryAfterSeconds()).isEqualTo(30);
                    assertThat(e.getMessage()).isEqualTo("No active GTFS feed yet.");
                });
    }
}
