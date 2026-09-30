package dev.pti.etl.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.analytics.core.adapter.out.metrics.MicrometerAnalyticsMetrics;
import dev.pti.analytics.core.domain.Detector;
import dev.pti.analytics.core.domain.Outcome;
import dev.pti.analytics.core.domain.Trigger;
import dev.pti.analytics.event.domain.InsightEvent;
import dev.pti.common.events.Audience;
import dev.pti.common.events.UiChannel;
import dev.pti.common.time.BusinessClock;
import dev.pti.etl.core.EtlSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** DOC-23 §4.1: which detector a micro-batch triggers, late batches, error isolation, events after the run. */
class DispatchBatchAnalyticsTest {

    private static final Instant NOW = Instant.parse("2026-09-29T21:20:00Z");
    private static final Instant COMMITTED = NOW.minusMillis(800);
    private static final UUID BATCH = UUID.fromString("0198a3c4-1111-7000-8000-000000000001");

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final CollectingSink sink = new CollectingSink();
    private final FakeRouteDetector bunching = new FakeRouteDetector(Detector.BUNCHING);
    private final FakeRouteDetector disruption = new FakeRouteDetector(Detector.DISRUPTION);

    private DispatchBatchAnalytics dispatch(FakeRouteDetector... detectors) {
        return new DispatchBatchAnalytics(
                List.of(detectors),
                new MicrometerAnalyticsMetrics(registry),
                sink,
                new BusinessClock(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ZERO));
    }

    private static BatchCommit batch(EtlSource source, Instant minEventTs, String... routes) {
        return new BatchCommit(
                BATCH, source, Set.of(routes), minEventTs, Instant.parse("2026-09-29T21:19:58Z"), COMMITTED);
    }

    private double lateBatches(String detector) {
        var counter = registry.find("pti.analytics.late.batches")
                .tag("detector", detector)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private double runs(String detector, String trigger, String outcome) {
        var counter = registry.find("pti.analytics.runs")
                .tags("detector", detector, "trigger", trigger, "outcome", outcome)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void aVehiclePositionBatchAdvancesOnlyBunchingOnEveryRouteOfTheBatch() {
        dispatch(bunching, disruption)
                .execute(batch(EtlSource.GTFS_RT_VEHICLE_POSITION, NOW.minusSeconds(3), "18", "5", "901"));

        assertThat(bunching.advancedRoutes()).as("in route id order").containsExactly("18", "5", "901");
        assertThat(disruption.advances()).isEmpty();
    }

    @Test
    void aTripUpdateBatchAdvancesOnlyDisruption() {
        dispatch(bunching, disruption).execute(batch(EtlSource.GTFS_RT_TRIP_UPDATE, NOW.minusSeconds(3), "18"));

        assertThat(disruption.advancedRoutes()).containsExactly("18");
        assertThat(bunching.advances()).isEmpty();
    }

    @Test
    void ticketingBatchesTriggerNothing() {
        DispatchSummary summary =
                dispatch(bunching, disruption).execute(batch(EtlSource.TICKETING_SALES, NOW.minusSeconds(3), "18"));

        assertThat(bunching.advances()).isEmpty();
        assertThat(disruption.advances()).isEmpty();
        assertThat(summary).isEqualTo(DispatchSummary.NONE);
        assertThat(registry.find("pti.analytics.dispatch.delay").timer()).isNull();
    }

    @Test
    void routesOutsideTheDetectorsRouteTypesAreSkipped() {
        bunching.disabledFor("901");

        dispatch(bunching).execute(batch(EtlSource.GTFS_RT_VEHICLE_POSITION, NOW.minusSeconds(3), "18", "901"));

        assertThat(bunching.advancedRoutes()).containsExactly("18");
    }

    @Test
    void theRunKnowsItsTriggerAndTheBatchThatCausedIt() {
        dispatch(bunching).execute(batch(EtlSource.GTFS_RT_VEHICLE_POSITION, NOW.minusSeconds(3), "18"));

        FakeRouteDetector.Advance advance = bunching.advances().getFirst();
        assertThat(advance.trigger()).isEqualTo(Trigger.BATCH);
        assertThat(advance.context().sourceBatchId()).isEqualTo(BATCH);
        assertThat(advance.context().minEventTs()).isEqualTo(NOW.minusSeconds(3));
        assertThat(advance.context().sourceRecordTs()).isEqualTo(Instant.parse("2026-09-29T21:19:58Z"));
        assertThat(advance.context().committedAt()).isEqualTo(COMMITTED);
        assertThat(advance.context().batchId()).isNotEqualTo(BATCH);
    }

    @Test
    void everyRunGetsItsOwnBatchIdAndTheLogContextNamesBothIds() {
        dispatch(bunching).execute(batch(EtlSource.GTFS_RT_VEHICLE_POSITION, NOW.minusSeconds(3), "18", "5"));

        List<FakeRouteDetector.Advance> advances = bunching.advances();
        assertThat(advances.get(0).context().batchId())
                .isNotEqualTo(advances.get(1).context().batchId());
        assertThat(advances).allSatisfy(advance -> {
            assertThat(advance.mdcBatchId())
                    .isEqualTo(advance.context().batchId().toString());
            assertThat(advance.mdcSource()).isEqualTo(BATCH.toString());
        });
        assertThat(MDC.get("batch_id"))
                .as("the log context is cleared after a run")
                .isNull();
        assertThat(MDC.get("source_batch_id")).isNull();
    }

    @Test
    void theDispatchDelayIsTheTimeFromTheCommitToTheStart() {
        dispatch(bunching).execute(batch(EtlSource.GTFS_RT_VEHICLE_POSITION, NOW.minusSeconds(3), "18"));

        assertThat(registry.get("pti.analytics.dispatch.delay").timer().totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(800);
    }

    @Test
    void aBatchThatStartsAtOrBeforeTheCursorIsLateAndCountedOnceHoweverManyRoutesAre() {
        bunching.cursorAt("18", NOW.minusSeconds(3)).cursorAt("5", NOW.minusSeconds(10));

        dispatch(bunching).execute(batch(EtlSource.GTFS_RT_VEHICLE_POSITION, NOW.minusSeconds(3), "18", "5", "7"));

        assertThat(lateBatches("bunching")).isEqualTo(1);
        assertThat(bunching.advancedRoutes())
                .as("late data is still advanced, never re-evaluated")
                .hasSize(3);
    }

    @Test
    void aBatchAfterTheCursorIsNotLate() {
        bunching.cursorAt("18", NOW.minusSeconds(4));

        dispatch(bunching).execute(batch(EtlSource.GTFS_RT_VEHICLE_POSITION, NOW.minusSeconds(3), "18", "7"));

        assertThat(lateBatches("bunching")).isZero();
    }

    @Test
    void lateBatchesAreCountedPerDetector() {
        bunching.cursorAt("18", NOW);
        disruption.cursorAt("18", NOW);
        DispatchBatchAnalytics dispatch = dispatch(bunching, disruption);

        dispatch.execute(batch(EtlSource.GTFS_RT_VEHICLE_POSITION, NOW.minusSeconds(3), "18"));
        dispatch.execute(batch(EtlSource.GTFS_RT_TRIP_UPDATE, NOW.minusSeconds(3), "18"));

        assertThat(lateBatches("bunching")).isEqualTo(1);
        assertThat(lateBatches("disruption")).isEqualTo(1);
    }

    @Test
    void aFailingRunIsCountedAndTheNextRouteStillRuns() {
        bunching.failingOn("18");

        DispatchSummary summary =
                dispatch(bunching).execute(batch(EtlSource.GTFS_RT_VEHICLE_POSITION, NOW.minusSeconds(3), "18", "5"));

        assertThat(bunching.advancedRoutes()).containsExactly("18", "5");
        assertThat(summary).isEqualTo(new DispatchSummary(2, 1));
        assertThat(runs("bunching", "batch", "error")).isEqualTo(1);
        assertThat(runs("bunching", "batch", "ok")).isEqualTo(1);
        assertThat(MDC.get("batch_id")).isNull();
    }

    @Test
    void aFailingCursorReadDoesNotStopTheRun() {
        bunching.whoseCursorFails();

        DispatchSummary summary =
                dispatch(bunching).execute(batch(EtlSource.GTFS_RT_VEHICLE_POSITION, NOW.minusSeconds(3), "18"));

        assertThat(summary).isEqualTo(new DispatchSummary(1, 0));
        assertThat(lateBatches("bunching")).isZero();
    }

    @Test
    void everyOutcomeIsCountedButOnlyRunsWithWorkAreTimed() {
        bunching.outcome(route -> switch (route) {
            case "18" -> Outcome.OK;
            case "5" -> Outcome.NOOP;
            default -> Outcome.SKIPPED_LOCKED;
        });

        dispatch(bunching).execute(batch(EtlSource.GTFS_RT_VEHICLE_POSITION, NOW.minusSeconds(3), "18", "5", "7"));

        assertThat(runs("bunching", "batch", "ok")).isEqualTo(1);
        assertThat(runs("bunching", "batch", "noop")).isEqualTo(1);
        assertThat(runs("bunching", "batch", "skipped_locked")).isEqualTo(1);
        assertThat(registry.get("pti.analytics.run")
                        .tag("detector", "bunching")
                        .timer()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void theEventsOfARunAreHandedToTheSinkOnlyWhenTheRunReturned() {
        bunching.emitting("18", event("bunching.opened", "e1")).emitting("5", event("bunching.opened", "e2"));
        bunching.failingOn("5");

        dispatch(bunching).execute(batch(EtlSource.GTFS_RT_VEHICLE_POSITION, NOW.minusSeconds(3), "18", "5"));

        assertThat(sink.events()).extracting(InsightEvent::key).containsExactly("e1");
    }

    @Test
    void aSinkThatThrowsAnywayDoesNotFailTheRun() {
        CollectingSink broken = new CollectingSink().failing();
        bunching.emitting("18", event("bunching.opened", "e1"));
        DispatchBatchAnalytics dispatch = new DispatchBatchAnalytics(
                List.of(bunching),
                new MicrometerAnalyticsMetrics(registry),
                broken,
                new BusinessClock(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ZERO));

        DispatchSummary summary =
                dispatch.execute(batch(EtlSource.GTFS_RT_VEHICLE_POSITION, NOW.minusSeconds(3), "18", "5"));

        assertThat(summary).isEqualTo(new DispatchSummary(2, 0));
        assertThat(bunching.advancedRoutes()).containsExactly("18", "5");
    }

    @Test
    void aBatchWithoutRoutesDoesNothingButStillMeasuresTheDelay() {
        DispatchSummary summary = dispatch(bunching)
                .execute(new BatchCommit(BATCH, EtlSource.GTFS_RT_VEHICLE_POSITION, Set.of(), null, null, COMMITTED));

        assertThat(summary).isEqualTo(new DispatchSummary(0, 0));
        assertThat(registry.get("pti.analytics.dispatch.delay").timer().count()).isEqualTo(1);
    }

    @Test
    void withoutDetectorsNothingHappens() {
        DispatchSummary summary =
                dispatch().execute(batch(EtlSource.GTFS_RT_VEHICLE_POSITION, NOW.minusSeconds(3), "18"));

        assertThat(summary).isEqualTo(new DispatchSummary(0, 0));
    }

    private static InsightEvent event(String type, String key) {
        return new InsightEvent(type, UiChannel.ALERTS, Audience.OPERATIONS, key, "18", null, null, Map.of());
    }
}
