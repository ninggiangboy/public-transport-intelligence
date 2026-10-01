package dev.pti.analytics.recompute.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.pti.analytics.core.domain.Detector;
import dev.pti.analytics.recompute.application.port.RecomputeMetrics;
import dev.pti.analytics.recompute.domain.DetectorStats;
import dev.pti.analytics.recompute.domain.ReplayRange;
import dev.pti.analytics.recompute.domain.ReplaySource;
import dev.pti.analytics.recompute.domain.WorkItem;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The plan by source and the accounting of {@code execute} (DOC-23 §11.1, AN-R-10) with in-memory detectors. */
class DetectorRecomputeServiceTest {

    private static final Instant MIN = Instant.parse("2026-09-29T12:00:00Z");
    private static final Instant MAX = Instant.parse("2026-09-29T13:00:00Z");
    private static final ReplayRange RANGE = new ReplayRange(MIN, MAX, null, null);

    private final Recorder metrics = new Recorder();
    private final List<FakeRecompute> all = new ArrayList<>(List.of(
            new FakeRecompute(Detector.BUNCHING),
            new FakeRecompute(Detector.DISRUPTION),
            new FakeRecompute(Detector.ETA),
            new FakeRecompute(Detector.OTP)));
    private final DetectorRecomputeService service = new DetectorRecomputeService(all, metrics);

    static Stream<Arguments> sources() {
        return Stream.of(
                Arguments.of(ReplaySource.GTFS_RT_VEHICLE_POSITION, List.of(Detector.BUNCHING)),
                Arguments.of(
                        ReplaySource.GTFS_RT_TRIP_UPDATE, List.of(Detector.DISRUPTION, Detector.ETA, Detector.OTP)),
                Arguments.of(ReplaySource.TICKETING_SALES, List.of()),
                Arguments.of(ReplaySource.TICKETING_SALE_POINTS, List.of()),
                Arguments.of(ReplaySource.GTFS_STATIC, List.of()));
    }

    @ParameterizedTest(name = "{0} plans {1}")
    @MethodSource("sources")
    @DisplayName("AN-R-10 the plan of a replay follows the table of DOC-23 §11.1")
    void thePlanOfAReplayFollowsTheTableOfSources(ReplaySource source, List<Detector> detectors) {
        List<WorkItem> items = service.plan(source, RANGE);

        assertThat(items).extracting(WorkItem::detector).containsExactlyElementsOf(detectors);
    }

    @Test
    void theReplayRangeGoesToTheDetectorsOwnReading() {
        all.get(1).replayFrom = MIN.minusSeconds(600);

        List<WorkItem> items = service.plan(ReplaySource.GTFS_RT_TRIP_UPDATE, RANGE);

        assertThat(items.getFirst().from())
                .as("disruption looks one window back")
                .isEqualTo(MIN.minusSeconds(600));
        assertThat(items.get(1).from()).as("ETA reads the range as it is").isEqualTo(MIN);
    }

    @Test
    void aJobPlanHasTheItemsOfTheRequestedDetectorsOnly() {
        List<WorkItem> items = service.plan(Set.of(Detector.OTP, Detector.BUNCHING, Detector.TICKETING), MIN, MAX);

        assertThat(items).extracting(WorkItem::detector).containsExactly(Detector.BUNCHING, Detector.OTP);
        assertThat(items).allSatisfy(item -> {
            assertThat(item.from()).isEqualTo(MIN);
            assertThat(item.to()).isEqualTo(MAX);
        });
    }

    @Test
    void anItemRunsInItsDetectorAndItsRowsAreCounted() {
        FakeRecompute bunching = all.getFirst();
        bunching.stats = new DetectorStats(1, 12, 3);
        UUID batch = UUID.randomUUID();
        WorkItem item = new WorkItem(Detector.BUNCHING, "18", MIN, MAX);

        DetectorStats stats = service.execute(item, batch);

        assertThat(stats).isEqualTo(new DetectorStats(1, 12, 3));
        assertThat(bunching.executed).containsExactly(item);
        assertThat(bunching.batches).containsExactly(batch);
        assertThat(metrics.rows).containsExactly("bunching upserted 12", "bunching deleted 3");
    }

    @Test
    void anItemWithoutABatchIdGetsANewOne() {
        FakeRecompute eta = all.get(2);

        service.execute(new WorkItem(Detector.ETA, "h", MIN, MIN));

        assertThat(eta.batches).singleElement().isNotNull();
    }

    @Test
    void anItemOfADetectorWithoutARecomputeIsRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.execute(new WorkItem(Detector.TICKETING, "w", MIN, MAX)))
                .withMessageContaining("TICKETING");
        assertThat(metrics.rows).isEmpty();
    }

    /** A detector recompute that records its calls. */
    private static final class FakeRecompute implements DetectorRecompute {

        private final Detector detector;
        final List<WorkItem> executed = new ArrayList<>();
        final List<UUID> batches = new ArrayList<>();
        DetectorStats stats = DetectorStats.NONE;
        Instant replayFrom = MIN;

        FakeRecompute(Detector detector) {
            this.detector = detector;
        }

        @Override
        public Detector detector() {
            return detector;
        }

        @Override
        public List<WorkItem> plan(Instant from, Instant to) {
            return List.of(new WorkItem(detector, "scope", from, to));
        }

        @Override
        public List<WorkItem> planReplay(ReplayRange range) {
            return plan(replayFrom, range.maxEventTs());
        }

        @Override
        public DetectorStats execute(WorkItem item, UUID batchId) {
            executed.add(item);
            batches.add(batchId);
            return stats;
        }
    }

    private static final class Recorder implements RecomputeMetrics {

        final List<String> rows = new ArrayList<>();

        @Override
        public void upserted(Detector detector, long count) {
            rows.add(detector.tag() + " upserted " + count);
        }

        @Override
        public void deleted(Detector detector, long count) {
            rows.add(detector.tag() + " deleted " + count);
        }
    }
}
