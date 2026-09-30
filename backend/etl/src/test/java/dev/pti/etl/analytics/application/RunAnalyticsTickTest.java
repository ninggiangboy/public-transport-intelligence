package dev.pti.etl.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.analytics.core.adapter.out.metrics.MicrometerAnalyticsMetrics;
import dev.pti.analytics.core.application.port.OpenEpisodeCounts;
import dev.pti.analytics.core.domain.Detector;
import dev.pti.analytics.core.domain.Trigger;
import dev.pti.common.time.BusinessClock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** DOC-23 §4.2: the tick advances the routes that hold state and refreshes the open-episode gauges. */
class RunAnalyticsTickTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final CollectingSink sink = new CollectingSink();
    private final FakeRouteDetector bunching = new FakeRouteDetector(Detector.BUNCHING);
    private final FakeRouteDetector disruption = new FakeRouteDetector(Detector.DISRUPTION);
    private boolean countsFail;

    private RunAnalyticsTick tick() {
        OpenEpisodeCounts counts = detector -> {
            if (countsFail) {
                throw new IllegalStateException("The database is gone");
            }
            return detector == Detector.BUNCHING ? 3 : 1;
        };
        return new RunAnalyticsTick(
                List.of(bunching, disruption),
                new MicrometerAnalyticsMetrics(registry),
                counts,
                sink,
                new BusinessClock(Clock.fixed(Instant.parse("2026-09-29T21:20:00Z"), ZoneOffset.UTC), Duration.ZERO));
    }

    @Test
    void everyRouteThatNeedsATickIsAdvancedByItsDetectorWithoutASourceBatch() {
        bunching.needingTick("18", "5");
        disruption.needingTick("18");

        DispatchSummary summary = tick().execute();

        assertThat(bunching.advancedRoutes()).containsExactly("18", "5");
        assertThat(disruption.advancedRoutes()).containsExactly("18");
        assertThat(summary).isEqualTo(new DispatchSummary(3, 0));
        assertThat(bunching.advances()).allSatisfy(advance -> {
            assertThat(advance.trigger()).isEqualTo(Trigger.TICK);
            assertThat(advance.context().sourceBatchId()).isNull();
            assertThat(advance.context().committedAt()).isNull();
            assertThat(advance.context().sourceRecordTs()).isNull();
        });
        assertThat(registry.get("pti.analytics.runs")
                        .tags("detector", "bunching", "trigger", "tick", "outcome", "ok")
                        .counter()
                        .count())
                .isEqualTo(2);
    }

    @Test
    void theOpenEpisodeGaugesAreCountedFromTheDatabase() {
        tick().execute();

        assertThat(registry.get("pti.analytics.open.episodes")
                        .tag("detector", "bunching")
                        .gauge()
                        .value())
                .isEqualTo(3);
        assertThat(registry.get("pti.analytics.open.episodes")
                        .tag("detector", "disruption")
                        .gauge()
                        .value())
                .isEqualTo(1);
    }

    @Test
    void aFailingCountDoesNotStopTheTick() {
        countsFail = true;
        bunching.needingTick("18");

        DispatchSummary summary = tick().execute();

        assertThat(summary).isEqualTo(new DispatchSummary(1, 0));
        assertThat(registry.find("pti.analytics.open.episodes").gauges()).isEmpty();
    }

    @Test
    void aFailingRunIsCountedAndTheOtherRoutesStillRun() {
        bunching.needingTick("18", "5").failingOn("18");

        DispatchSummary summary = tick().execute();

        assertThat(bunching.advancedRoutes()).containsExactly("18", "5");
        assertThat(summary).isEqualTo(new DispatchSummary(2, 1));
        assertThat(registry.get("pti.analytics.runs")
                        .tags("detector", "bunching", "trigger", "tick", "outcome", "error")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void aDetectorWhoseRouteListFailsIsCountedAndTheOtherDetectorStillRuns() {
        bunching.whoseTickListFails();
        disruption.needingTick("18");

        DispatchSummary summary = tick().execute();

        assertThat(disruption.advancedRoutes()).containsExactly("18");
        assertThat(summary).isEqualTo(new DispatchSummary(1, 1));
        assertThat(registry.get("pti.analytics.runs")
                        .tags("detector", "bunching", "trigger", "tick", "outcome", "error")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void withoutDetectorsTheTickOnlyRefreshesTheGauges() {
        RunAnalyticsTick tick = new RunAnalyticsTick(
                List.of(),
                new MicrometerAnalyticsMetrics(registry),
                detector -> 0,
                sink,
                new BusinessClock(Clock.systemUTC(), Duration.ZERO));

        assertThat(tick.execute()).isEqualTo(DispatchSummary.NONE);
        assertThat(registry.find("pti.analytics.open.episodes").gauges()).hasSize(2);
    }
}
