package dev.pti.analytics.disruption.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.analytics.config.AnalyticsPropertiesFixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The bucket loop of DOC-23 §6.4: snapshots at whole hours (AN-D-17) and the same result however it is split. */
class DisruptionReplayTest {

    private static final String ROUTE = "18";
    private static final Instant T0 = Instant.parse("2026-09-29T12:00:00Z");

    private final DisruptionThresholds thresholds =
            AnalyticsPropertiesFixtures.defaults().disruption().toThresholds();
    private final DisruptionReplay replay = new DisruptionReplay(thresholds);

    private static Map<Integer, DirectionState> warmedUp(Instant lastBucket) {
        Map<Integer, DirectionState> states = new LinkedHashMap<>();
        for (int direction : DisruptionReplay.DIRECTIONS) {
            states.put(direction, new DirectionState(new BaselineState(63, 891, 61, lastBucket, 0, 0, null), null));
        }
        return states;
    }

    private static List<Instant> ends(Instant after, int count) {
        List<Instant> ends = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            ends.add(after.plus(Duration.ofMinutes(i)));
        }
        return ends;
    }

    /** Six arrivals a minute per direction: normal delay, a burst on direction 0 between minute 70 and 110. */
    private static List<Arrival> traffic(int minutes) {
        Random random = new Random(7);
        List<Arrival> arrivals = new ArrayList<>();
        for (int minute = 0; minute < minutes; minute++) {
            for (int direction = 0; direction < 2; direction++) {
                for (int i = 0; i < 6; i++) {
                    boolean burst = direction == 0 && minute >= 70 && minute < 110;
                    int delay = (burst ? 300 : 60) + random.nextInt(21) - 10;
                    arrivals.add(new Arrival(
                            direction,
                            "S" + i,
                            T0.plus(Duration.ofMinutes(minute)).plusSeconds(i * 9L),
                            delay));
                }
            }
        }
        return arrivals;
    }

    @Test
    @DisplayName("AN-D-17 the bucket that ends on a whole hour gives a snapshot equal to the state after it")
    void anD17() {
        // The buckets end 12:01 … 14:00; whole hours are 13:00 and 14:00.
        List<Object[]> snapshots = new ArrayList<>();

        DisruptionReplay.Result result = replay.run(
                ROUTE,
                warmedUp(T0),
                new ArrivalSeries(traffic(120), thresholds),
                ends(T0, 120),
                (hour, direction, state) -> snapshots.add(new Object[] {hour, direction, state}));

        assertThat(snapshots).hasSize(4);
        assertThat(snapshots)
                .extracting(s -> s[0])
                .containsExactly(
                        Instant.parse("2026-09-29T13:00:00Z"),
                        Instant.parse("2026-09-29T13:00:00Z"),
                        Instant.parse("2026-09-29T14:00:00Z"),
                        Instant.parse("2026-09-29T14:00:00Z"));
        BaselineState last = (BaselineState) snapshots.getLast()[2];
        assertThat(last).isEqualTo(result.states().get(1).baseline());
        assertThat(((BaselineState) snapshots.getLast()[2]).lastBucket())
                .isEqualTo(Instant.parse("2026-09-29T14:00:00Z"));
    }

    @Test
    @DisplayName("AN-D-19 the same data in one run or in many runs gives identical episodes and state")
    void anD19() {
        ArrivalSeries series = new ArrivalSeries(traffic(150), thresholds);
        List<Instant> all = ends(T0, 150);

        DisruptionReplay.Result once = replay.run(ROUTE, warmedUp(T0), series, all, (h, d, s) -> {});

        Map<Integer, DirectionState> states = warmedUp(T0);
        List<EpisodeChange> changes = new ArrayList<>();
        for (int from = 0; from < all.size(); from += 7) {
            DisruptionReplay.Result part = replay.run(
                    ROUTE, states, series, all.subList(from, Math.min(from + 7, all.size())), (h, d, s) -> {});
            states = part.states();
            changes.addAll(part.changes());
        }

        assertThat(states).isEqualTo(once.states());
        assertThat(changes).isEqualTo(once.changes());
        assertThat(once.changes())
                .extracting(EpisodeChange::kind)
                .contains(EpisodeChange.Kind.OPENED, EpisodeChange.Kind.CLOSED);
        assertThat(once.changes().stream()
                        .filter(c -> c.kind() == EpisodeChange.Kind.OPENED)
                        .map(c -> c.episode().directionId()))
                .containsOnly(0);
    }

    @Test
    @DisplayName("a direction that is ahead of a bucket does not process it again")
    void aDirectionAheadIsSkipped() {
        Map<Integer, DirectionState> states = warmedUp(T0);
        states.put(
                1,
                new DirectionState(new BaselineState(63, 891, 61, T0.plus(Duration.ofMinutes(5)), 0, 0, null), null));

        DisruptionReplay.Result result =
                replay.run(ROUTE, states, new ArrivalSeries(traffic(10), thresholds), ends(T0, 5), (h, d, s) -> {});

        assertThat(result.states().get(0).baseline().lastBucket()).isEqualTo(T0.plus(Duration.ofMinutes(5)));
        assertThat(result.states().get(1)).isEqualTo(states.get(1));
    }
}
