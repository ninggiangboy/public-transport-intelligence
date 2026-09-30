package dev.pti.analytics.bunching.adapter.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.analytics.bunching.domain.CloseReason;
import dev.pti.analytics.bunching.domain.PassSource;
import dev.pti.analytics.bunching.domain.SkipReason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** The names and labels of DOC-23 §14.1 for the bunching counters. */
class MicrometerBunchingMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerBunchingMetrics metrics = new MicrometerBunchingMetrics(registry);

    private double evaluations(String result, String reason) {
        return registry.get("pti.analytics.bunching.evaluations")
                .tags("result", result, "reason", reason)
                .counter()
                .count();
    }

    @Test
    void evaluatedFollowersAreCountedByHowTheLeaderWasFound() {
        metrics.evaluated(PassSource.OBSERVED, 3);
        metrics.evaluated(PassSource.OBSERVED, 2);
        metrics.evaluated(PassSource.ESTIMATED, 1);

        assertThat(evaluations("evaluated", "observed")).isEqualTo(5);
        assertThat(evaluations("evaluated", "estimated")).isEqualTo(1);
    }

    @Test
    void skippedFollowersAreCountedByReason() {
        metrics.skipped(SkipReason.NO_LEADER, 4);
        metrics.skipped(SkipReason.HEADWAY_TOO_LONG, 1);

        assertThat(evaluations("skipped", "no_leader")).isEqualTo(4);
        assertThat(evaluations("skipped", "headway_too_long")).isEqualTo(1);
    }

    @Test
    void episodesAreCountedWhenTheyOpenAndWhenTheyCloseWithTheReason() {
        metrics.episodeOpened();
        metrics.episodeClosed(CloseReason.GAP_RECOVERED);
        metrics.episodeClosed(CloseReason.GAP_RECOVERED);
        metrics.episodeClosed(CloseReason.OUT_OF_ZONE);

        assertThat(registry.get("pti.analytics.episodes")
                        .tags("detector", "bunching", "event", "opened", "reason", "none")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get("pti.analytics.episodes")
                        .tags("detector", "bunching", "event", "closed", "reason", "gap_recovered")
                        .counter()
                        .count())
                .isEqualTo(2);
        assertThat(registry.get("pti.analytics.episodes")
                        .tags("detector", "bunching", "event", "closed", "reason", "out_of_zone")
                        .counter()
                        .count())
                .isEqualTo(1);
    }
}
