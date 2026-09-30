package dev.pti.analytics.disruption.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.analytics.alert.domain.AlertRecord;
import dev.pti.analytics.config.AnalyticsPropertiesFixtures;
import dev.pti.analytics.core.domain.Detector;
import dev.pti.analytics.core.domain.LockNames;
import dev.pti.analytics.core.domain.Outcome;
import dev.pti.analytics.core.domain.RunContext;
import dev.pti.analytics.core.domain.RunResult;
import dev.pti.analytics.core.domain.Trigger;
import dev.pti.analytics.disruption.application.DisruptionFakes.Alerts;
import dev.pti.analytics.disruption.application.DisruptionFakes.EpisodeMetrics;
import dev.pti.analytics.disruption.application.DisruptionFakes.Lock;
import dev.pti.analytics.disruption.application.DisruptionFakes.Metrics;
import dev.pti.analytics.disruption.application.DisruptionFakes.NoLimits;
import dev.pti.analytics.disruption.application.DisruptionFakes.Reference;
import dev.pti.analytics.disruption.application.DisruptionFakes.Store;
import dev.pti.analytics.disruption.application.DisruptionFakes.Transactions;
import dev.pti.analytics.disruption.domain.BaselineState;
import dev.pti.analytics.disruption.domain.CloseReason;
import dev.pti.analytics.disruption.domain.DisruptionEpisode;
import dev.pti.analytics.disruption.domain.DisruptionThresholds;
import dev.pti.analytics.event.domain.InsightEvent;
import dev.pti.analytics.reference.domain.RouteInfo;
import dev.pti.common.events.Audience;
import dev.pti.common.time.BusinessClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link DisruptionDetector} against in-memory ports: the order of work of DOC-23 §6.4, the alert and the UI events of
 * §10 and the scenarios of §18.3 that are about more than the state machine (AN-D-15, AN-D-16, AN-D-20 … AN-D-22).
 */
class DisruptionDetectorTest {

    private static final String ROUTE = "18";
    private static final Instant T0 = Instant.parse("2026-09-29T12:00:00Z");

    private final DisruptionThresholds thresholds =
            AnalyticsPropertiesFixtures.defaults().disruption().toThresholds();
    private final Store store = new Store();
    private final Alerts alerts = new Alerts();
    private final Lock lock = new Lock();
    private final NoLimits limits = new NoLimits();
    private final Transactions tx = new Transactions();
    private final Metrics metrics = new Metrics();
    private final EpisodeMetrics episodeMetrics = new EpisodeMetrics();
    private final Reference reference = new Reference();

    DisruptionDetectorTest() {
        reference.routes.put(ROUTE, new RouteInfo(ROUTE, 3, "18", Map.of(0, "Northbound", 1, "Southbound")));
        reference.routes.put("RAIL", new RouteInfo("RAIL", 2, "Blue", Map.of()));
    }

    private DisruptionDetector detectorAt(Instant now) {
        return detectorAt(now, true);
    }

    private DisruptionDetector detectorAt(Instant now, boolean enabled) {
        return new DisruptionDetector(
                enabled,
                thresholds,
                store,
                reference,
                alerts,
                lock,
                limits,
                tx,
                metrics,
                episodeMetrics,
                new BusinessClock(Clock.fixed(now, ZoneOffset.UTC), Duration.ZERO));
    }

    private RunResult advance(Instant now, Trigger trigger) {
        return detectorAt(now).advance(ROUTE, trigger, RunContext.untriggered(RunResult.newBatchId()));
    }

    /** A direction with a warmed-up baseline (AN-D-xx default: 61 buckets, μ = 63, var = 891) at {@code T0}. */
    private void warmBaselines(int bucketCount) {
        for (int direction : new int[] {0, 1}) {
            store.saveBaseline(ROUTE, direction, new BaselineState(63, 891, bucketCount, T0, 0, 0, null));
        }
    }

    /** Six arrivals a minute from minute {@code from} up to {@code to}, all with {@code delay}. */
    private void traffic(int direction, int from, int to, int delay) {
        for (int minute = from; minute < to; minute++) {
            for (int i = 0; i < 6; i++) {
                store.arrive(
                        direction, "S" + i, T0.plus(Duration.ofMinutes(minute)).plusSeconds(i * 9L), delay);
            }
        }
    }

    private static List<String> types(RunResult result) {
        return result.events().stream().map(InsightEvent::type).toList();
    }

    private AlertRecord alertOf(DisruptionEpisode episode) {
        return alerts.byKey.get("disruption:" + episode.id());
    }

    private DisruptionEpisode onlyEpisode() {
        assertThat(store.episodes).hasSize(1);
        return store.episodes.values().iterator().next();
    }

    /** A burst of delay on the northbound direction from 12:10 to 12:30, normal traffic everywhere else. */
    private RunResult openAnEpisode() {
        warmBaselines(61);
        traffic(0, 0, 10, 60);
        traffic(0, 10, 30, 300);
        traffic(1, 0, 30, 60);
        return advance(Instant.parse("2026-09-29T12:31:00Z"), Trigger.BATCH);
    }

    @Test
    @DisplayName("AN-D-21 a disruption opens one episode, a PUBLIC alert, disruption.opened and alert.created")
    void anD21() {
        RunResult result = openAnEpisode();

        DisruptionEpisode episode = onlyEpisode();
        assertThat(result.outcome()).isEqualTo(Outcome.OK);
        assertThat(result.opened()).isEqualTo(1);
        assertThat(episode.directionId()).isZero();
        assertThat(episode.isOpen()).isTrue();
        assertThat(episode.start()).isEqualTo(Instant.parse("2026-09-29T12:13:00Z"));
        assertThat(episode.baselineMean()).isBetween(63.0, 100.0);
        AlertRecord alert = alertOf(episode);
        assertThat(alert.audience()).isEqualTo(Audience.PUBLIC);
        assertThat(alert.title()).isEqualTo("Delays on route 18 Northbound");
        assertThat(alert.refTable()).isEqualTo("insight.insight_service_disruption");
        assertThat(alert.refId()).isEqualTo(episode.id().toString());
        assertThat(alert.body())
                .containsEntry("disruptionId", episode.id().toString())
                .containsEntry("directionId", 0)
                .containsKeys("episodeStart", "currentAvgDelaySeconds", "baselineMeanSeconds", "zScore");
        assertThat(result.events())
                .filteredOn(e -> e.type().equals("disruption.opened"))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.audience()).isEqualTo(Audience.PUBLIC);
                    assertThat(event.key()).isEqualTo(episode.id().toString());
                    assertThat(event.routeId()).isEqualTo(ROUTE);
                    assertThat(event.data())
                            .containsKeys(
                                    "id",
                                    "routeId",
                                    "directionId",
                                    "episodeStart",
                                    "currentAvgDelaySeconds",
                                    "baselineMeanSeconds",
                                    "zScore",
                                    "affectedStopIds");
                });
        assertThat(types(result).indexOf("disruption.opened"))
                .isLessThan(types(result).indexOf("alert.created"));
        assertThat(episodeMetrics.opened).isEqualTo(1);
    }

    @Test
    @DisplayName("AN-D-15 the alert rises to severity 2 once the peak reaches 4, with alert.updated exactly once")
    void anD15() {
        RunResult result = openAnEpisode();

        DisruptionEpisode episode = onlyEpisode();
        assertThat(episode.peakZ()).isGreaterThanOrEqualTo(4.0);
        assertThat(alertOf(episode).severity()).isEqualTo(2);
        assertThat(alertOf(episode).body()).containsKey("peakZScore");
        assertThat(types(result)).containsExactly("disruption.opened", "alert.created", "alert.updated");
    }

    @Test
    @DisplayName("recovery closes the episode as RECOVERED with disruption.closed and alert.updated")
    void recoveryClosesTheEpisode() {
        openAnEpisode();
        traffic(0, 30, 60, 60);
        traffic(1, 30, 60, 60);

        RunResult result = advance(Instant.parse("2026-09-29T13:01:00Z"), Trigger.TICK);

        DisruptionEpisode episode = onlyEpisode();
        assertThat(episode.isOpen()).isFalse();
        assertThat(episode.closeReason()).isEqualTo(CloseReason.RECOVERED);
        assertThat(episode.end()).isAfter(Instant.parse("2026-09-29T12:30:00Z"));
        assertThat(result.closed()).isEqualTo(1);
        assertThat(types(result)).containsExactly("disruption.closed", "alert.updated");
        assertThat(alertOf(episode).resolvedAt()).isNotNull();
        assertThat(alertOf(episode).body())
                .containsEntry("closeReason", "RECOVERED")
                .containsKeys("episodeEnd", "peakZScore");
        assertThat(episodeMetrics.closed).containsExactly(CloseReason.RECOVERED);
        assertThat(store.baselines.get(ROUTE).get(0).openEpisodeId()).isNull();
    }

    @Test
    @DisplayName("AN-D-22 after triage narrowed the alert to ENGINEERING the closing events carry that audience")
    void anD22() {
        openAnEpisode();
        alerts.narrowTo("disruption:" + onlyEpisode().id(), Audience.ENGINEERING);
        traffic(0, 30, 60, 60);
        traffic(1, 30, 60, 60);

        RunResult result = advance(Instant.parse("2026-09-29T13:01:00Z"), Trigger.TICK);

        assertThat(result.events())
                .extracting(InsightEvent::type)
                .containsExactly("disruption.closed", "alert.updated");
        assertThat(result.events()).extracting(InsightEvent::audience).containsOnly(Audience.ENGINEERING);
    }

    @Test
    @DisplayName("running the same data again adds no episode, no alert and no event")
    void rerunAddsNothing() {
        openAnEpisode();
        traffic(0, 30, 60, 60);
        traffic(1, 30, 60, 60);
        Instant later = Instant.parse("2026-09-29T13:00:00Z");
        advance(later, Trigger.TICK);
        DisruptionEpisode before = onlyEpisode();
        AlertRecord alertBefore = alertOf(before);
        // Reset the cursor and the state but keep the rows, as AN-BS-14 does for bunching.
        store.baselines.clear();
        warmBaselines(61);

        RunResult result = advance(later, Trigger.TICK);

        assertThat(result.outcome()).isEqualTo(Outcome.OK);
        assertThat(result.events()).isEmpty();
        assertThat(onlyEpisode()).isEqualTo(before);
        assertThat(alerts.byKey).hasSize(1);
        assertThat(alertOf(before)).isEqualTo(alertBefore);
    }

    @Test
    @DisplayName("the same data advanced in many small steps gives the same episode and state as in one")
    void splitRunsAgree() {
        warmBaselines(61);
        traffic(0, 0, 10, 60);
        traffic(0, 10, 30, 300);
        traffic(0, 30, 60, 60);
        traffic(1, 0, 60, 60);
        Instant end = Instant.parse("2026-09-29T13:00:00Z");
        for (Instant now = Instant.parse("2026-09-29T12:04:00Z"); !now.isAfter(end); now = now.plusSeconds(97)) {
            advance(now, Trigger.TICK);
        }
        advance(end, Trigger.TICK);
        DisruptionEpisode stepwise = onlyEpisode();
        BaselineState stepwiseState = store.baselines.get(ROUTE).get(0);

        store.episodes.clear();
        store.baselines.clear();
        alerts.byKey.clear();
        warmBaselines(61);
        advance(end, Trigger.TICK);

        assertThat(onlyEpisode()).isEqualTo(stepwise);
        assertThat(store.baselines.get(ROUTE).get(0)).isEqualTo(stepwiseState);
    }

    @Test
    @DisplayName("AN-D-16 both directions with a burst give two episodes with different ids")
    void anD16() {
        warmBaselines(61);
        for (int direction : new int[] {0, 1}) {
            traffic(direction, 0, 10, 60);
            traffic(direction, 10, 30, 300);
        }

        RunResult result = advance(Instant.parse("2026-09-29T12:31:00Z"), Trigger.BATCH);

        assertThat(store.episodes).hasSize(2);
        assertThat(store.episodes.values())
                .extracting(DisruptionEpisode::directionId)
                .containsExactlyInAnyOrder(0, 1);
        assertThat(result.opened()).isEqualTo(2);
        assertThat(alerts.byKey).hasSize(2);
        assertThat(alerts.byKey.values())
                .extracting(AlertRecord::title)
                .containsExactlyInAnyOrder("Delays on route 18 Northbound", "Delays on route 18 Southbound");
    }

    @Test
    @DisplayName("no episode opens during the warm-up, however high the delay")
    void noEpisodeDuringWarmUp() {
        warmBaselines(10);
        traffic(0, 0, 10, 60);
        traffic(0, 10, 30, 300);
        traffic(1, 0, 30, 60);

        RunResult result = advance(Instant.parse("2026-09-29T12:31:00Z"), Trigger.BATCH);

        assertThat(store.episodes).isEmpty();
        assertThat(alerts.byKey).isEmpty();
        assertThat(result.events()).isEmpty();
        assertThat(store.baselines.get(ROUTE).get(0).bucketCount()).isGreaterThan(10);
    }

    @Test
    @DisplayName("the idle tick closes an episode of a route that stopped sending arrivals as NO_DATA")
    void idleTickClosesWithNoData() {
        openAnEpisode();
        assertThat(detectorAt(T0).routesNeedingTick()).containsExactly(ROUTE);

        RunResult result = advance(Instant.parse("2026-09-29T13:00:00Z"), Trigger.TICK);

        assertThat(onlyEpisode().closeReason()).isEqualTo(CloseReason.NO_DATA);
        assertThat(types(result)).containsExactly("disruption.closed", "alert.updated");
    }

    @Test
    @DisplayName("hourly snapshots are written for both directions at whole hours")
    void snapshotsAtWholeHours() {
        warmBaselines(61);
        traffic(0, 0, 70, 60);
        traffic(1, 0, 70, 60);

        advance(Instant.parse("2026-09-29T13:11:00Z"), Trigger.BATCH);

        assertThat(store.snapshots.get(ROUTE).keySet()).containsExactly(Instant.parse("2026-09-29T13:00:00Z"));
        Map<Integer, BaselineState> snapshot = store.snapshots.get(ROUTE).get(Instant.parse("2026-09-29T13:00:00Z"));
        assertThat(snapshot).containsOnlyKeys(0, 1);
        assertThat(snapshot.get(0).lastBucket()).isEqualTo(Instant.parse("2026-09-29T13:00:00Z"));
        assertThat(store.baselines.get(ROUTE).get(0).lastBucket()).isEqualTo(Instant.parse("2026-09-29T13:10:00Z"));
    }

    @Test
    @DisplayName("a run with the lock held elsewhere is skipped and writes nothing")
    void skippedLocked() {
        warmBaselines(61);
        traffic(0, 0, 30, 300);
        lock.heldElsewhere = true;

        RunResult result = advance(Instant.parse("2026-09-29T12:31:00Z"), Trigger.BATCH);

        assertThat(result.outcome()).isEqualTo(Outcome.SKIPPED_LOCKED);
        assertThat(lock.names).containsExactly(LockNames.disruption(ROUTE));
        assertThat(store.episodes).isEmpty();
        assertThat(store.baselines.get(ROUTE).get(0).lastBucket()).isEqualTo(T0);
    }

    @Test
    @DisplayName("the write transaction has a 20 second statement timeout and is new")
    void transactionShape() {
        warmBaselines(61);
        traffic(0, 0, 30, 60);

        advance(Instant.parse("2026-09-29T12:31:00Z"), Trigger.BATCH);

        assertThat(tx.newTransactions).isEqualTo(1);
        assertThat(limits.statementTimeout).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    @DisplayName("a route whose grid is evaluated up to the watermark returns NOOP without a transaction")
    void noopWhenUpToDate() {
        warmBaselines(61);
        traffic(0, 0, 30, 60);
        Instant now = Instant.parse("2026-09-29T12:31:00Z");
        advance(now, Trigger.BATCH);
        int transactions = tx.newTransactions;

        RunResult second = advance(now.plusSeconds(5), Trigger.BATCH);

        assertThat(second.outcome()).isEqualTo(Outcome.NOOP);
        assertThat(tx.newTransactions).isEqualTo(transactions);
    }

    @Test
    @DisplayName("AN-D-20 a route whose route_type is not configured is not enabled and advance returns NOOP")
    void anD20() {
        DisruptionDetector detector = detectorAt(Instant.parse("2026-09-29T12:31:00Z"));

        assertThat(detector.enabledFor("RAIL")).isFalse();
        assertThat(detector.enabledFor("UNKNOWN")).isFalse();
        assertThat(detector.enabledFor(ROUTE)).isTrue();
        RunResult result = detector.advance("RAIL", Trigger.BATCH, RunContext.untriggered(UUID.randomUUID()));
        assertThat(result.outcome()).isEqualTo(Outcome.NOOP);
        assertThat(tx.newTransactions).isZero();
    }

    @Test
    @DisplayName("without an ACTIVE feed advance returns NOOP")
    void noFeed() {
        reference.active = false;
        DisruptionDetector detector = detectorAt(Instant.parse("2026-09-29T12:31:00Z"));

        assertThat(detector.enabledFor(ROUTE)).isTrue();
        assertThat(detector.advance(ROUTE, Trigger.BATCH, RunContext.untriggered(UUID.randomUUID()))
                        .outcome())
                .isEqualTo(Outcome.NOOP);
        assertThat(tx.newTransactions).isZero();
    }

    @Test
    @DisplayName("a disabled detector answers no route, needs no tick and never runs")
    void disabled() {
        warmBaselines(61);
        store.saveBaseline(ROUTE, 0, new BaselineState(63, 891, 61, T0, 1, 0, null));
        DisruptionDetector detector = detectorAt(Instant.parse("2026-09-29T12:31:00Z"), false);

        assertThat(detector.enabledFor(ROUTE)).isFalse();
        assertThat(detector.routesNeedingTick()).isEmpty();
        assertThat(detector.advance(ROUTE, Trigger.TICK, RunContext.untriggered(UUID.randomUUID()))
                        .outcome())
                .isEqualTo(Outcome.NOOP);
        assertThat(detector.detector()).isEqualTo(Detector.DISRUPTION);
    }

    @Test
    @DisplayName("a route more than max-catch-up behind jumps ahead and counts the skipped buckets that had data")
    void catchUpLimit() {
        warmBaselines(61);
        traffic(0, 0, 60, 60);

        RunResult result = advance(Instant.parse("2026-09-29T14:01:00Z"), Trigger.TICK);

        assertThat(result.outcome()).isEqualTo(Outcome.OK);
        assertThat(metrics.skippedTicks).isEqualTo(60);
        assertThat(result.gridPoints()).isEqualTo(60);
        assertThat(store.baselines.get(ROUTE).get(0).lastBucket()).isEqualTo(Instant.parse("2026-09-29T14:00:00Z"));
    }

    @Test
    @DisplayName("skipping buckets without arrivals is not counted")
    void catchUpOverSilence() {
        warmBaselines(61);

        advance(Instant.parse("2026-09-29T14:01:00Z"), Trigger.TICK);

        assertThat(metrics.skippedTicks).isZero();
    }

    @Test
    @DisplayName("a new route starts one bucket before the first bucket of the micro-batch that reached it")
    void newRouteStartsAtTheBatch() {
        traffic(0, 0, 30, 60);
        Instant minEventTs = Instant.parse("2026-09-29T12:20:30Z");
        RunContext context = RunContext.afterBatch(
                RunResult.newBatchId(), UUID.randomUUID(), minEventTs, T0, Instant.parse("2026-09-29T12:31:00Z"));

        RunResult result = detectorAt(Instant.parse("2026-09-29T12:31:00Z")).advance(ROUTE, Trigger.BATCH, context);

        assertThat(result.gridPoints()).isEqualTo(11);
        assertThat(store.baselines.get(ROUTE)).containsOnlyKeys(0, 1);
        assertThat(store.baselines.get(ROUTE).get(1).bucketCount()).isZero();
        assertThat(store.baselines.get(ROUTE).get(0).bucketCount()).isPositive();
    }
}
