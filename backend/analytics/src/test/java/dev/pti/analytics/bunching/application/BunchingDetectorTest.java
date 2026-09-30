package dev.pti.analytics.bunching.application;

import static dev.pti.analytics.bunching.domain.BunchingFixtures.DAY;
import static dev.pti.analytics.bunching.domain.BunchingFixtures.at;
import static dev.pti.analytics.bunching.domain.BunchingFixtures.lat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import dev.pti.analytics.bunching.application.port.BunchingMetrics;
import dev.pti.analytics.bunching.domain.BunchingEpisode;
import dev.pti.analytics.bunching.domain.BunchingFixtures;
import dev.pti.analytics.bunching.domain.CloseReason;
import dev.pti.analytics.bunching.domain.PassSource;
import dev.pti.analytics.bunching.domain.SkipReason;
import dev.pti.analytics.bunching.domain.StopStatus;
import dev.pti.analytics.bunching.domain.VehiclePosition;
import dev.pti.analytics.core.application.port.AnalyticsMetrics;
import dev.pti.analytics.core.application.port.TransactionLimits;
import dev.pti.analytics.core.domain.Detector;
import dev.pti.analytics.core.domain.LockNames;
import dev.pti.analytics.core.domain.Outcome;
import dev.pti.analytics.core.domain.RunContext;
import dev.pti.analytics.core.domain.RunResult;
import dev.pti.analytics.core.domain.Trigger;
import dev.pti.analytics.event.domain.InsightEvent;
import dev.pti.analytics.reference.domain.RouteInfo;
import dev.pti.common.events.Audience;
import dev.pti.common.time.BusinessClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The use case of DOC-23 §5.6 and §10 on in-memory ports: the grid and the cursor, the fast path, the lock, the
 * episode with its alert and events, repeating a run, and the catch-up limit. Two vehicles run the ten-stop trip of the
 * fixture at 500 m a minute. {@code L} starts at 12:00:00 and {@code F} two minutes later, so the gap between them is
 * about 120 s against a headway of 600 s: a bunched pair.
 */
class BunchingDetectorTest {

    private static final String ROUTE = "R";

    private final InMemoryBunching.Store store = new InMemoryBunching.Store();
    private final InMemoryBunching.History history = new InMemoryBunching.History();
    private final InMemoryBunching.Alerts alerts = new InMemoryBunching.Alerts(at("12:00:00"));
    private final InMemoryBunching.Reference reference = new InMemoryBunching.Reference();
    private final InMemoryBunching.Lock lock = new InMemoryBunching.Lock();
    private final InMemoryBunching.Transactions tx = new InMemoryBunching.Transactions();
    private final InMemoryBunching.ManualClock manual = new InMemoryBunching.ManualClock(at("12:05:10"));
    private final TransactionLimits limits = mock(TransactionLimits.class);
    private final AnalyticsMetrics metrics = mock(AnalyticsMetrics.class);
    private final BunchingMetrics bunchingMetrics = mock(BunchingMetrics.class);
    private BunchingDetector detector;

    @BeforeEach
    void detector() {
        reference.routes.put(ROUTE, new RouteInfo(ROUTE, 3, "18", Map.of(0, "Northbound")));
        reference.routes.put("901", new RouteInfo("901", 0, "901", Map.of()));
        reference.trips.put(BunchingFixtures.TRIP, BunchingFixtures.trip());
        detector = new BunchingDetector(
                store,
                history,
                store,
                alerts,
                reference,
                lock,
                limits,
                metrics,
                bunchingMetrics,
                tx,
                new BusinessClock(manual, Duration.ZERO),
                BunchingFixtures.thresholds());
    }

    /** Positions every 5 s of a vehicle that left the first stop at {@code start}, from {@code from} to {@code to}. */
    private void drive(String vehicle, String start, String from, String to) {
        Instant begin = at(start);
        for (Instant t = at(from); !t.isAfter(at(to)); t = t.plusSeconds(5)) {
            double progress = Math.min(
                    4500, Math.max(0, 500.0 * Duration.between(begin, t).getSeconds() / 60));
            int stop = (int) Math.ceil(progress / 500);
            boolean atStop = progress % 500 == 0;
            history.all.add(new VehiclePosition(
                    vehicle,
                    t,
                    DAY,
                    BunchingFixtures.TRIP,
                    0,
                    lat(progress),
                    BunchingFixtures.LON,
                    stop + 1,
                    atStop ? StopStatus.STOPPED_AT : StopStatus.IN_TRANSIT_TO));
        }
    }

    private void bunchedPair(String until) {
        drive("L", "12:00:00", "12:00:00", until);
        drive("F", "12:02:00", "12:02:00", until);
    }

    private RunContext batch() {
        return RunContext.afterBatch(
                RunResult.newBatchId(), UUID.randomUUID(), at("12:03:00"), at("12:05:00"), at("12:05:01"));
    }

    private static List<String> types(RunResult result) {
        return result.events().stream().map(InsightEvent::type).toList();
    }

    @Test
    @DisplayName("AN-BS-18 opening an episode writes one episode and one alert; bunching.opened then alert.created")
    void opensAnEpisodeWithItsAlertAndEvents() {
        bunchedPair("12:05:00");

        RunResult result = detector.advance(ROUTE, Trigger.BATCH, batch());

        assertThat(result.outcome()).isEqualTo(Outcome.OK);
        assertThat(result.opened()).isEqualTo(1);
        assertThat(result.closed()).isZero();
        assertThat(result.gridPoints()).isPositive();
        assertThat(store.episodes).hasSize(1);
        BunchingEpisode episode = store.episodes.values().iterator().next();
        assertThat(episode.isOpen()).isTrue();
        assertThat(episode.leader()).isEqualTo("L");
        assertThat(episode.follower()).isEqualTo("F");
        assertThat(episode.episodeStart()).isEqualTo(at("12:03:15"));
        assertThat(episode.scheduledHeadwaySeconds()).isEqualTo(600);
        assertThat(episode.thresholdSeconds()).isEqualTo(300);
        assertThat(episode.minGapSeconds()).isBetween(119, 121);
        assertThat(episode.openStopId()).isEqualTo("S2");
        assertThat(store.cursors).containsEntry(ROUTE, at("12:04:45"));
        assertThat(store.pairStates(ROUTE))
                .singleElement()
                .satisfies(s -> assertThat(s.openEpisodeId()).isEqualTo(episode.id()));
        assertThat(alerts.byDedupKey).containsOnlyKeys("bunching:" + episode.id());
        assertThat(alerts.byDedupKey.values().iterator().next().title())
                .isEqualTo("Bus bunching on route 18 Northbound: vehicles L and F");
        assertThat(types(result)).containsExactly("bunching.opened", "alert.created");
        InsightEvent opened = result.events().getFirst();
        assertThat(opened.audience()).isEqualTo(Audience.OPERATIONS);
        assertThat(opened.key()).isEqualTo(episode.id().toString());
        assertThat(opened.sourceRecordTs()).isEqualTo(at("12:05:00"));
        assertThat(opened.committedAt()).isEqualTo(at("12:05:01"));
        assertThat(opened.data())
                .containsEntry("id", episode.id().toString())
                .containsEntry("routeId", ROUTE)
                .containsEntry("vehicleLeader", "L")
                .containsEntry("vehicleFollower", "F")
                .containsEntry("headwaySeconds", 600)
                .containsEntry("stopId", "S2")
                .containsEntry("episodeStart", "2026-09-29T12:03:15Z");
        assertThat(lock.taken).containsExactly(LockNames.bunching(ROUTE));
        verify(limits).statementTimeout(Duration.ofSeconds(20));
        assertThat(store.batchOfEpisode.get(episode.id())).isEqualTo(result.batchId());
    }

    @Test
    @DisplayName("an advance that is already up to the watermark is a NOOP: no transaction, no lock")
    void noopFastPath() {
        bunchedPair("12:05:00");
        detector.advance(ROUTE, Trigger.BATCH, batch());
        int transactions = tx.opened;
        int locks = lock.taken.size();

        RunResult second = detector.advance(ROUTE, Trigger.TICK, RunContext.untriggered(RunResult.newBatchId()));

        assertThat(second.outcome()).isEqualTo(Outcome.NOOP);
        assertThat(tx.opened).isEqualTo(transactions);
        assertThat(lock.taken).hasSize(locks);
    }

    @Test
    @DisplayName("AN-BS-14 running again over the same data, rows kept: same id, no new row, no event")
    void runningAgainChangesNothing() {
        bunchedPair("12:05:00");
        detector.advance(ROUTE, Trigger.BATCH, batch());
        BunchingEpisode first = store.episodes.values().iterator().next();
        store.resetCursorAndStates(ROUTE);

        RunResult again = detector.advance(ROUTE, Trigger.BATCH, batch());

        assertThat(again.outcome()).isEqualTo(Outcome.OK);
        assertThat(store.episodes).hasSize(1);
        assertThat(store.episodes.values().iterator().next()).isEqualTo(first);
        assertThat(again.events()).isEmpty();
        assertThat(again.opened()).isZero();
        assertThat(alerts.byDedupKey).hasSize(1);
    }

    @Test
    @DisplayName("AN-BS-13 the same data in one run or in several gives the same episode, state and cursor")
    void theSplitIntoRunsDoesNotMatter() {
        bunchedPair("12:05:00");
        detector.advance(ROUTE, Trigger.BATCH, batch());
        BunchingEpisode whole = store.episodes.values().iterator().next();
        var wholeStates = store.pairStates(ROUTE);
        var wholeCursor = store.cursors.get(ROUTE);

        InMemoryBunching.Store stepwise = new InMemoryBunching.Store();
        BunchingDetector other = new BunchingDetector(
                stepwise,
                history,
                stepwise,
                new InMemoryBunching.Alerts(at("12:00:00")),
                reference,
                lock,
                limits,
                metrics,
                bunchingMetrics,
                tx,
                new BusinessClock(manual, Duration.ZERO),
                BunchingFixtures.thresholds());
        for (String now : List.of("12:03:40", "12:03:55", "12:04:20", "12:04:50", "12:05:10")) {
            manual.set(at(now));
            other.advance(ROUTE, Trigger.BATCH, batch());
        }

        assertThat(stepwise.episodes.values().iterator().next()).isEqualTo(whole);
        assertThat(stepwise.pairStates(ROUTE)).isEqualTo(wholeStates);
        assertThat(stepwise.cursors.get(ROUTE)).isEqualTo(wholeCursor);
    }

    @Test
    @DisplayName(
            "a pair that stops reporting closes SIGNAL_LOST at the last point it was seen: bunching.closed, alert.updated")
    void closesWhenTheSignalIsLost() {
        bunchedPair("12:05:00");
        detector.advance(ROUTE, Trigger.BATCH, batch());
        BunchingEpisode open = store.episodes.values().iterator().next();
        manual.set(at("12:08:10"));

        RunResult tick = detector.advance(ROUTE, Trigger.TICK, RunContext.untriggered(RunResult.newBatchId()));

        assertThat(tick.outcome()).isEqualTo(Outcome.OK);
        assertThat(tick.closed()).isEqualTo(1);
        BunchingEpisode closed = store.episodes.get(open.id());
        assertThat(closed.closeReason()).isEqualTo(CloseReason.SIGNAL_LOST);
        assertThat(closed.episodeEnd()).isEqualTo(at("12:06:45"));
        assertThat(types(tick)).containsExactly("bunching.closed", "alert.updated");
        assertThat(tick.events().getFirst().data())
                .containsEntry("closeReason", "SIGNAL_LOST")
                .containsEntry("episodeEnd", "2026-09-29T12:06:45Z");
        assertThat(tick.events().getFirst().sourceRecordTs()).isNull();
        assertThat(alerts.byDedupKey.get("bunching:" + open.id()).resolvedAt()).isNotNull();
        assertThat(alerts.byDedupKey.get("bunching:" + open.id()).body()).containsEntry("closeReason", "SIGNAL_LOST");
        assertThat(store.pairStates(ROUTE)).isEmpty();
        verify(bunchingMetrics).episodeClosed(CloseReason.SIGNAL_LOST);
        assertThat(detector.routesNeedingTick()).isEmpty();
    }

    @Test
    @DisplayName("an open episode keeps its route on the tick list")
    void openEpisodesNeedTheTick() {
        bunchedPair("12:05:00");
        detector.advance(ROUTE, Trigger.BATCH, batch());

        assertThat(detector.routesNeedingTick()).containsExactly(ROUTE);
        assertThat(detector.cursor(ROUTE)).contains(at("12:04:45"));
        assertThat(detector.cursor("other")).isEmpty();
    }

    @Test
    @DisplayName("AN-I-03 the lock is held elsewhere: SKIPPED_LOCKED, nothing written; the next run continues")
    void skippedWhenLocked() {
        bunchedPair("12:05:00");
        lock.heldElsewhere = true;

        RunResult skipped = detector.advance(ROUTE, Trigger.BATCH, batch());

        assertThat(skipped.outcome()).isEqualTo(Outcome.SKIPPED_LOCKED);
        assertThat(skipped.events()).isEmpty();
        assertThat(store.cursors).isEmpty();
        assertThat(store.episodes).isEmpty();

        lock.heldElsewhere = false;
        assertThat(detector.advance(ROUTE, Trigger.BATCH, batch()).outcome()).isEqualTo(Outcome.OK);
    }

    @Test
    @DisplayName("the cursor is read again under the lock: a route another pod advanced is a NOOP")
    void anotherPodAlreadyAdvancedTheRoute() {
        bunchedPair("12:05:00");
        // The fast path sees no cursor; by the time this run has the lock another pod has moved it to the watermark.
        InMemoryBunching.Store racing = new InMemoryBunching.Store() {
            private int reads;

            @Override
            public java.util.Optional<Instant> cursor(String routeId) {
                return reads++ == 0 ? java.util.Optional.empty() : super.cursor(routeId);
            }
        };
        racing.cursors.put(ROUTE, at("12:04:45"));
        BunchingDetector racer = new BunchingDetector(
                racing,
                history,
                racing,
                alerts,
                reference,
                lock,
                limits,
                metrics,
                bunchingMetrics,
                tx,
                new BusinessClock(manual, Duration.ZERO),
                BunchingFixtures.thresholds());

        RunResult result = racer.advance(ROUTE, Trigger.BATCH, batch());

        assertThat(result.outcome()).isEqualTo(Outcome.NOOP);
        assertThat(tx.opened).isEqualTo(1);
        assertThat(racing.episodes).isEmpty();
    }

    @Test
    @DisplayName("AN-BG-12 a route that is not a bus route is not evaluated")
    void railIsNotEvaluated() {
        bunchedPair("12:05:00");

        assertThat(detector.enabledFor("901")).isFalse();
        RunResult result = detector.advance("901", Trigger.BATCH, batch());

        assertThat(result.outcome()).isEqualTo(Outcome.NOOP);
        assertThat(tx.opened).isZero();
        assertThat(detector.enabledFor(ROUTE)).isTrue();
        assertThat(detector.enabledFor("no-such-route")).isFalse();
    }

    @Test
    @DisplayName("without an ACTIVE feed nothing is evaluated (DOC-23 §15)")
    void noActiveFeed() {
        bunchedPair("12:05:00");
        reference.active = false;

        assertThat(detector.enabledFor(ROUTE)).isFalse();
        assertThat(detector.advance(ROUTE, Trigger.BATCH, batch()).outcome()).isEqualTo(Outcome.NOOP);
        assertThat(tx.opened).isZero();
    }

    @Test
    @DisplayName(
            "AN-BS-17 the cursor is 20 minutes behind and the gap has data: it jumps to W - 15 min, skipped ticks +20")
    void catchUpSkipsGridPointsThatHadData() {
        bunchedPair("12:20:00");
        manual.set(at("12:20:10"));
        store.cursors.put(ROUTE, at("11:59:45"));

        RunResult result = detector.advance(ROUTE, Trigger.TICK, RunContext.untriggered(RunResult.newBatchId()));

        assertThat(result.outcome()).isEqualTo(Outcome.OK);
        verify(metrics).skippedTicks(Detector.BUNCHING, 20);
        // From the jump (12:04:45) to the last grid point (12:19:45) there are 60 points left to evaluate.
        assertThat(result.gridPoints()).isEqualTo(60);
        assertThat(store.cursors).containsEntry(ROUTE, at("12:19:45"));
    }

    @Test
    @DisplayName("a catch-up over an interval without data skips the points but does not count them")
    void catchUpOverAnEmptyInterval() {
        drive("L", "12:19:00", "12:19:00", "12:20:00");
        manual.set(at("12:20:10"));
        store.cursors.put(ROUTE, at("11:00:00"));

        RunResult result = detector.advance(ROUTE, Trigger.TICK, RunContext.untriggered(RunResult.newBatchId()));

        assertThat(result.outcome()).isEqualTo(Outcome.OK);
        verify(metrics, never()).skippedTicks(any(), anyLong());
        assertThat(store.cursors).containsEntry(ROUTE, at("12:19:45"));
    }

    @Test
    @DisplayName("evaluations are counted by result and reason, after the commit")
    void evaluationMetrics() {
        bunchedPair("12:05:00");

        detector.advance(ROUTE, Trigger.BATCH, batch());

        verify(bunchingMetrics).episodeOpened();
        verify(bunchingMetrics).evaluated(org.mockito.ArgumentMatchers.eq(PassSource.OBSERVED), anyLong());
        verify(bunchingMetrics).skipped(org.mockito.ArgumentMatchers.eq(SkipReason.FIRST_STOPS), anyLong());
    }

    @Test
    @DisplayName("a failure inside the transaction propagates and leaves no metrics or events behind")
    void aFailureRollsBack() {
        bunchedPair("12:05:00");
        InMemoryBunching.Store failing = new InMemoryBunching.Store() {
            @Override
            public void saveCursor(String routeId, Instant lastTick) {
                throw new IllegalStateException("The database is gone");
            }
        };
        BunchingDetector broken = new BunchingDetector(
                failing,
                history,
                failing,
                alerts,
                reference,
                lock,
                limits,
                metrics,
                bunchingMetrics,
                tx,
                new BusinessClock(manual, Duration.ZERO),
                BunchingFixtures.thresholds());

        org.assertj.core.api.Assertions.assertThatIllegalStateException()
                .isThrownBy(() -> broken.advance(ROUTE, Trigger.BATCH, batch()));

        verify(bunchingMetrics, never()).episodeOpened();
    }
}
