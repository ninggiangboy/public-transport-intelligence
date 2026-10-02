package dev.pti.analytics.recompute.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AN-R-09: the windows of a ticketing replay are chosen by {@code created_at}, and only closed ones count. */
class TicketingWindowsTest {

    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final Duration LATENESS = Duration.ofMinutes(2);

    private static Instant at(String time) {
        return Instant.parse("2026-09-29T" + time + "Z");
    }

    private static List<Instant> windows(String min, String max, String now) {
        return TicketingWindows.closedWindows(at(min), at(max), at(now), WINDOW, LATENESS);
    }

    @Test
    @DisplayName("AN-R-09 the windows are the ones that touch the created_at range")
    void theWindowsAreTheOnesThatTouchTheRange() {
        assertThat(windows("12:07:00", "12:31:00", "14:00:00"))
                .containsExactly(at("12:00:00"), at("12:15:00"), at("12:30:00"));
    }

    @Test
    @DisplayName("AN-R-09 a window that is not closed yet is not processed")
    void aWindowThatIsNotClosedIsLeftOut() {
        // 12:30–12:45 ends at 12:45; it closes at 12:47, two minutes of allowed lateness later.
        assertThat(windows("12:07:00", "12:40:00", "12:46:59")).containsExactly(at("12:00:00"), at("12:15:00"));
        assertThat(windows("12:07:00", "12:40:00", "12:47:00"))
                .containsExactly(at("12:00:00"), at("12:15:00"), at("12:30:00"));
    }

    @Test
    void aRangeOnTheBoundaryTouchesBothWindows() {
        assertThat(windows("12:15:00", "12:15:00", "14:00:00")).containsExactly(at("12:15:00"));
        assertThat(windows("12:14:59", "12:15:00", "14:00:00")).containsExactly(at("12:00:00"), at("12:15:00"));
    }

    @Test
    void aRangeInTheFutureHasNoClosedWindow() {
        assertThat(windows("15:00:00", "15:20:00", "14:00:00")).isEmpty();
    }
}
