package dev.pti.analytics.disruption.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.pti.analytics.config.AnalyticsPropertiesFixtures;
import dev.pti.common.id.InsightIds;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The rows AN-D-01 … AN-D-14, AN-D-16 and AN-D-18 of DOC-23 §18.3 against the pure state machine. */
class DisruptionStateMachineTest {

    private static final String ROUTE = "18";
    private static final Instant START = Instant.parse("2026-09-29T12:00:00Z");
    private static final Duration MINUTE = Duration.ofMinutes(1);

    private final DisruptionThresholds thresholds =
            AnalyticsPropertiesFixtures.defaults().disruption().toThresholds();
    private final DisruptionStateMachine machine = new DisruptionStateMachine(thresholds);

    /** The mutable driver of one direction: feeds buckets one minute apart and remembers what happened. */
    private final class Direction {
        DirectionState state;
        Instant lastEnd;
        final List<EpisodeChange> changes = new ArrayList<>();
        final int directionId;

        Direction(int directionId, double mean, double variance, int bucketCount) {
            this.directionId = directionId;
            this.lastEnd = START;
            this.state = new DirectionState(new BaselineState(mean, variance, bucketCount, START, 0, 0, null), null);
        }

        /** The default state of DOC-23 §18.3: 61 buckets, μ = 63, var = 891. */
        Direction() {
            this(0, 63, 891, 61);
        }

        BucketObservation bucket(int samples, double mean, Map<String, Integer> stops) {
            lastEnd = lastEnd.plus(MINUTE);
            return new BucketObservation(lastEnd, samples, mean, stops);
        }

        EpisodeChange feed(double x) {
            return feed(5, x, Map.of());
        }

        EpisodeChange feed(int samples, double x, Map<String, Integer> stops) {
            BucketObservation bucket = bucket(samples, x, stops);
            DisruptionStateMachine.Step step = machine.process(ROUTE, directionId, state, bucket);
            state = step.state();
            if (step.change() != null) {
                changes.add(step.change());
            }
            return step.change();
        }

        void feedAll(double... values) {
            for (double x : values) {
                feed(x);
            }
        }

        void feedEmpty(int count) {
            for (int i = 0; i < count; i++) {
                feed(0, 0, Map.of());
            }
        }

        BaselineState baseline() {
            return state.baseline();
        }

        DisruptionEpisode episode() {
            return state.episode();
        }

        DisruptionEpisode lastEpisode() {
            return changes.getLast().episode();
        }
    }

    /** Opens an episode on the default state with x = 150, x = 160 (AN-D-06). */
    private Direction open() {
        Direction direction = new Direction();
        direction.feedAll(150, 160);
        assertThat(direction.episode()).isNotNull();
        return direction;
    }

    @Test
    @DisplayName("AN-D-01 z against the baseline before the update; then the EWMA update")
    void anD01() {
        Direction direction = new Direction(0, 60, 900, 61);
        assertThat(machine.zScore(90, direction.baseline())).isCloseTo(1.00, within(1e-9));

        direction.feed(90);

        assertThat(direction.baseline().mean()).isCloseTo(63.0, within(1e-9));
        assertThat(direction.baseline().variance()).isCloseTo(891.0, within(1e-9));
        assertThat(direction.baseline().bucketCount()).isEqualTo(62);
    }

    @Test
    @DisplayName("AN-D-02 the first bucket with data initialises the baseline and computes no z")
    void anD02() {
        Direction direction = new Direction(0, 0, 0, 0);

        EpisodeChange change = direction.feed(45);

        assertThat(change).isNull();
        assertThat(direction.baseline().mean()).isEqualTo(45);
        assertThat(direction.baseline().variance()).isZero();
        assertThat(direction.baseline().bucketCount()).isEqualTo(1);
        assertThat(direction.baseline().lastBucket()).isEqualTo(START.plus(MINUTE));
    }

    @Test
    @DisplayName("AN-D-03 a bucket with 4 samples changes neither the baseline nor the bucket count, and resets highs")
    void anD03() {
        Direction direction = new Direction();
        direction.feed(150);
        assertThat(direction.baseline().consecutiveHigh()).isEqualTo(1);
        BaselineState before = direction.baseline();

        direction.feed(4, 999, Map.of());

        assertThat(direction.baseline().mean()).isEqualTo(before.mean());
        assertThat(direction.baseline().variance()).isEqualTo(before.variance());
        assertThat(direction.baseline().bucketCount()).isEqualTo(before.bucketCount());
        assertThat(direction.baseline().consecutiveHigh()).isZero();
    }

    @Test
    @DisplayName("AN-D-04 the sigma floor applies and the comparison is strict: z = 2.50 is not high")
    void anD04() {
        Direction direction = new Direction(0, 60, 100, 61);
        assertThat(machine.zScore(135, direction.baseline())).isCloseTo(2.5, within(1e-9));

        direction.feed(135);

        assertThat(direction.baseline().consecutiveHigh()).isZero();
        assertThat(direction.baseline().mean()).isCloseTo(67.5, within(1e-9));
        assertThat(direction.baseline().variance()).isCloseTo(596.25, within(1e-9));
    }

    @Test
    @DisplayName("AN-D-05 during the warm-up no episode opens and every bucket updates the baseline")
    void anD05() {
        Direction direction = new Direction(0, 60, 900, 59);

        EpisodeChange change = direction.feed(300);

        assertThat(change).isNull();
        assertThat(direction.baseline().mean()).isCloseTo(84, within(1e-9));
        assertThat(direction.baseline().variance()).isCloseTo(5994, within(1e-9));
        assertThat(direction.baseline().bucketCount()).isEqualTo(60);
    }

    @Test
    @DisplayName("AN-D-06 two high buckets open an episode that starts at the first of them; the baseline is frozen")
    void anD06() {
        Direction direction = new Direction();

        assertThat(direction.feed(150)).isNull();
        assertThat(direction.baseline().consecutiveHigh()).isEqualTo(1);
        assertThat(direction.baseline().mean())
                .as("an outlier does not update the baseline")
                .isEqualTo(63);
        assertThat(machine.zScore(150, direction.baseline())).isCloseTo(2.90, within(0.005));

        EpisodeChange change = direction.feed(160);

        assertThat(change.kind()).isEqualTo(EpisodeChange.Kind.OPENED);
        DisruptionEpisode episode = change.episode();
        assertThat(episode.start()).isEqualTo(START); // the start of the bucket that ends one minute after START
        assertThat(episode.id()).isEqualTo(InsightIds.disruption(ROUTE, 0, START));
        assertThat(episode.baselineMean()).isEqualTo(63.0);
        assertThat(episode.baselineStddev()).isEqualTo(29.8);
        assertThat(episode.currentAvg()).isEqualTo(160.0);
        assertThat(episode.currentZ()).isEqualTo(3.23);
        assertThat(episode.peakAvg()).isEqualTo(160.0);
        assertThat(episode.peakZ()).isEqualTo(3.23);
        assertThat(episode.sampleCount()).isEqualTo(5);
        assertThat(episode.isOpen()).isTrue();
        assertThat(direction.baseline().openEpisodeId()).isEqualTo(episode.id());
        assertThat(direction.baseline().consecutiveHigh()).isZero();
        assertThat(direction.baseline().consecutiveLow()).isZero();
    }

    @Test
    @DisplayName("AN-D-07 a normal bucket after a high one resets the count and updates the baseline")
    void anD07() {
        Direction direction = new Direction();

        direction.feedAll(150, 70);

        assertThat(direction.baseline().consecutiveHigh()).isZero();
        assertThat(direction.baseline().mean()).isCloseTo(63.7, within(1e-9));
        assertThat(direction.baseline().variance()).isCloseTo(806.31, within(1e-9));
        assertThat(direction.changes).isEmpty();
    }

    @Test
    @DisplayName("AN-D-08 a bucket with too few samples between two high ones breaks the run")
    void anD08() {
        Direction direction = new Direction();

        direction.feed(150);
        direction.feed(3, 999, Map.of());
        direction.feed(160);

        assertThat(direction.changes).isEmpty();
        assertThat(direction.baseline().consecutiveHigh()).isEqualTo(1);
    }

    @Test
    @DisplayName("AN-D-09 three buckets below close-z close the episode as RECOVERED; the baseline stays frozen")
    void anD09() {
        Direction direction = open();

        direction.feedAll(100, 100);
        assertThat(direction.episode()).isNotNull();
        EpisodeChange change = direction.feed(100);

        assertThat(change.kind()).isEqualTo(EpisodeChange.Kind.CLOSED);
        assertThat(change.episode().closeReason()).isEqualTo(CloseReason.RECOVERED);
        assertThat(change.episode().end()).isEqualTo(direction.lastEnd);
        assertThat(direction.episode()).isNull();
        assertThat(direction.baseline().openEpisodeId()).isNull();
        assertThat(direction.baseline().mean()).as("frozen during the episode").isEqualTo(63);
        assertThat(direction.baseline().variance()).isEqualTo(891);
        assertThat(direction.baseline().consecutiveLow()).isZero();
    }

    @Test
    @DisplayName("AN-D-10 a bucket at z = 1.57 restarts the count of low buckets")
    void anD10() {
        Direction direction = open();

        direction.feedAll(100, 110, 100, 100);
        assertThat(direction.episode()).as("the 110 restarted the count").isNotNull();
        direction.feed(100);

        assertThat(direction.episode()).isNull();
        assertThat(direction.lastEpisode().closeReason()).isEqualTo(CloseReason.RECOVERED);
    }

    @Test
    @DisplayName("AN-D-11 three buckets without samples close the episode as NO_DATA")
    void anD11() {
        Direction direction = open();

        direction.feedEmpty(2);
        assertThat(direction.episode()).isNotNull();
        direction.feedEmpty(1);

        assertThat(direction.episode()).isNull();
        assertThat(direction.lastEpisode().closeReason()).isEqualTo(CloseReason.NO_DATA);
    }

    @Test
    @DisplayName("AN-D-12 the reason is NO_DATA when the bucket that closes the episode has too few samples")
    void anD12() {
        Direction direction = open();

        direction.feedAll(100, 100);
        direction.feedEmpty(1);

        assertThat(direction.episode()).isNull();
        assertThat(direction.lastEpisode().closeReason()).isEqualTo(CloseReason.NO_DATA);
    }

    @Test
    @DisplayName("AN-D-13 the first bucket with data after the close updates the baseline as usual")
    void anD13() {
        Direction direction = open();
        direction.feedAll(100, 100, 100);

        direction.feed(100);

        assertThat(direction.baseline().mean()).isCloseTo(66.7, within(1e-9));
        assertThat(direction.baseline().variance()).isCloseTo(925.11, within(1e-9));
    }

    @Test
    @DisplayName("AN-D-14 an episode that reaches max-episode-duration closes as MAX_DURATION and re-seeds the mean")
    void anD14() {
        Direction direction = open();
        Instant episodeStart = direction.episode().start();
        long minutesUntilLimit = Duration.between(direction.lastEnd, episodeStart.plus(Duration.ofHours(3)))
                .toMinutes();

        for (int i = 1; i < minutesUntilLimit; i++) {
            direction.feed(200);
            assertThat(direction.episode())
                    .as("still open %d minutes before the limit", minutesUntilLimit - i)
                    .isNotNull();
        }
        EpisodeChange change = direction.feed(210);

        assertThat(change.kind()).isEqualTo(EpisodeChange.Kind.CLOSED);
        assertThat(change.episode().closeReason()).isEqualTo(CloseReason.MAX_DURATION);
        assertThat(change.episode().end()).isEqualTo(episodeStart.plus(Duration.ofHours(3)));
        assertThat(direction.baseline().mean()).isEqualTo(210);
        assertThat(direction.baseline().variance())
                .as("variance and warm-up counter are kept")
                .isEqualTo(891);
        assertThat(direction.baseline().bucketCount()).isEqualTo(61);
        assertThat(direction.baseline().openEpisodeId()).isNull();
    }

    @Test
    @DisplayName("AN-D-16 the two directions of a route open their own episodes with different ids")
    void anD16() {
        Direction north = new Direction(0, 63, 891, 61);
        Direction south = new Direction(1, 63, 891, 61);

        north.feedAll(150, 160);
        south.feedAll(150, 160);

        assertThat(north.lastEpisode().id()).isNotEqualTo(south.lastEpisode().id());
        assertThat(north.lastEpisode().start()).isEqualTo(south.lastEpisode().start());
        assertThat(north.lastEpisode().directionId()).isZero();
        assertThat(south.lastEpisode().directionId()).isEqualTo(1);
    }

    @Test
    @DisplayName("AN-D-18 affected stops are those with an arrival at or above baseline + open-z × sigma_eff")
    void anD18() {
        Direction direction = new Direction();
        direction.feedAll(150);
        // The threshold is 63 + 2.5 × 30 = 138.
        direction.feed(5, 160, Map.of("A", 200, "B", 100, "C", 150));
        assertThat(direction.episode().affectedStopIds()).containsExactly("A", "C");

        direction.feed(5, 160, Map.of("A", 90, "B", 137, "D", 138));

        assertThat(direction.episode().affectedStopIds()).containsExactly("A", "C", "D");
    }

    @Test
    @DisplayName("AN-D-18 at most 100 affected stops are kept, the first 100 in order of stop id")
    void affectedStopsAreCapped() {
        Direction direction = new Direction();
        direction.feed(150);
        Map<String, Integer> stops = new TreeMap<>();
        for (int i = 0; i < 120; i++) {
            stops.put("S%03d".formatted(i), 300);
        }

        direction.feed(5, 160, stops);

        assertThat(direction.episode().affectedStopIds()).hasSize(100);
        assertThat(direction.episode().affectedStopIds().getFirst()).isEqualTo("S000");
        assertThat(direction.episode().affectedStopIds().getLast()).isEqualTo("S099");
    }

    @Test
    @DisplayName("AN-D-15 the peak follows the highest z while the episode is open")
    void peakFollowsTheHighestZ() {
        Direction direction = open();

        direction.feedAll(190, 200, 120);

        DisruptionEpisode episode = direction.episode();
        assertThat(episode.peakZ()).isEqualTo(4.57);
        assertThat(episode.peakAvg()).isEqualTo(200.0);
        assertThat(episode.currentAvg()).isEqualTo(120.0);
        assertThat(episode.currentZ()).isEqualTo(1.9);
    }

    @Test
    @DisplayName("a z-score beyond NUMERIC(6,2) is cut at 9999.99")
    void zIsClamped() {
        Direction direction = new Direction(0, 0, 0, 61);
        direction.feed(0);
        direction.feed(1_000_000);
        direction.feed(1_000_000);

        assertThat(direction.lastEpisode().currentZ()).isEqualTo(9999.99);
    }

    @Test
    @DisplayName("an episode read back from the database behaves like the one held in memory")
    void rehydratedEpisodeBehavesTheSame() {
        Direction direction = open();
        Direction reloaded = new Direction();
        reloaded.state = new DirectionState(direction.baseline(), direction.episode());
        reloaded.lastEnd = direction.lastEnd;

        direction.feedAll(170, 100, 100, 100);
        reloaded.feedAll(170, 100, 100, 100);

        assertThat(reloaded.lastEpisode()).isEqualTo(direction.lastEpisode());
        assertThat(reloaded.baseline()).isEqualTo(direction.baseline());
    }
}
