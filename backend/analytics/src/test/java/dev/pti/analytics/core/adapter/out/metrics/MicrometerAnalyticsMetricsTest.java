package dev.pti.analytics.core.adapter.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.analytics.core.domain.Detector;
import dev.pti.analytics.core.domain.Outcome;
import dev.pti.analytics.core.domain.Trigger;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The generic metrics of DOC-23 §14.1: names and labels. */
class MicrometerAnalyticsMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerAnalyticsMetrics metrics = new MicrometerAnalyticsMetrics(registry);

    @Test
    void runsAreCountedPerDetectorTriggerAndOutcome() {
        metrics.run(Detector.BUNCHING, Trigger.BATCH, Outcome.OK);
        metrics.run(Detector.BUNCHING, Trigger.BATCH, Outcome.OK);
        metrics.run(Detector.BUNCHING, Trigger.TICK, Outcome.SKIPPED_LOCKED);

        assertThat(registry.get("pti.analytics.runs")
                        .tags("detector", "bunching", "trigger", "batch", "outcome", "ok")
                        .counter()
                        .count())
                .isEqualTo(2);
        assertThat(registry.get("pti.analytics.runs")
                        .tags("detector", "bunching", "trigger", "tick", "outcome", "skipped_locked")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void durationsAndDelaysAreTimers() {
        metrics.runDuration(Detector.DISRUPTION, Duration.ofMillis(40));
        metrics.dispatchDelay(Duration.ofMillis(300));

        assertThat(registry.get("pti.analytics.run")
                        .tag("detector", "disruption")
                        .timer()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get("pti.analytics.dispatch.delay").timer().totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(300);
    }

    @Test
    void lateBatchesSkippedTicksAndDropsAreCounters() {
        metrics.lateBatch(Detector.BUNCHING);
        metrics.skippedTicks(Detector.DISRUPTION, 260);
        metrics.dropped();

        assertThat(registry.get("pti.analytics.late.batches")
                        .tag("detector", "bunching")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get("pti.analytics.skipped.ticks")
                        .tag("detector", "disruption")
                        .counter()
                        .count())
                .isEqualTo(260);
        assertThat(registry.get("pti.analytics.dropped").counter().count()).isEqualTo(1);
    }

    @Test
    void theOpenEpisodeGaugeFollowsTheLastCount() {
        metrics.openEpisodes(Detector.BUNCHING, 7);
        metrics.openEpisodes(Detector.BUNCHING, 3);

        assertThat(registry.get("pti.analytics.open.episodes")
                        .tag("detector", "bunching")
                        .gauge()
                        .value())
                .isEqualTo(3);
    }

    @Test
    void nothingIsRegisteredBeforeTheFirstUse() {
        assertThat(registry.getMeters()).isEmpty();
    }
}
