package dev.pti.analytics.bunching.domain;

import static dev.pti.analytics.bunching.domain.BunchingFixtures.at;
import static dev.pti.analytics.bunching.domain.BunchingFixtures.thresholds;
import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.common.id.InsightIds;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The state machine of a pair, the table of DOC-23 §18.2 (AN-BS-01 to AN-BS-12; the others are about the use case and
 * run against the database). {@code H = 600}: a pair opens below 300 s and closes above 420 s; grid points
 * {@code t1, t2, …} are 15 s apart.
 */
class PairStateMachineTest {

    private static final Instant T0 = at("12:00:00");

    private final PairStateMachine machine = new PairStateMachine("R", thresholds());

    private static Instant t(int n) {
        return T0.plusSeconds(15L * n);
    }

    private static Evaluation eval(String leader, String follower, String leaderTrip, int gap, int headway) {
        return new Evaluation(leader, follower, leaderTrip, "TF", 0, "S4", gap, headway, PassSource.OBSERVED);
    }

    private static VehiclePosition position(String vehicle, String trip, Instant at) {
        return new VehiclePosition(
                vehicle, at, BunchingFixtures.DAY, trip, 0, 44.9, -93.27, 5, StopStatus.IN_TRANSIT_TO);
    }

    /** The grid point {@code n} with one evaluation of (L, F) and both vehicles active. */
    private static TickEvaluation tick(int n, int gap) {
        return tick(n, gap, 600);
    }

    private static TickEvaluation tick(int n, int gap, int headway) {
        return new TickEvaluation(t(n), List.of(eval("L", "F", "TL", gap, headway)), active(n, "TL", "TF"), Map.of());
    }

    private static Map<String, VehiclePosition> active(int n, String leaderTrip, String followerTrip) {
        Map<String, VehiclePosition> active = new LinkedHashMap<>();
        active.put("F", position("F", followerTrip, t(n)));
        active.put("L", position("L", leaderTrip, t(n)));
        return active;
    }

    private List<EpisodeChange> run(int... gaps) {
        List<EpisodeChange> all = new ArrayList<>();
        for (int i = 0; i < gaps.length; i++) {
            all.addAll(machine.apply(tick(i + 1, gaps[i])));
        }
        return all;
    }

    private static List<EpisodeChange.Kind> kinds(List<EpisodeChange> changes) {
        return changes.stream().map(EpisodeChange::kind).toList();
    }

    @Test
    @DisplayName(
            "AN-BS-01 gaps 310, 290, 280: opens at t3 with episode_start t2, two evaluations, min 280, threshold 300")
    void opensAfterTwoEvaluationsBelow() {
        assertThat(machine.apply(tick(1, 310))).isEmpty();
        assertThat(machine.apply(tick(2, 290))).isEmpty();
        assertThat(machine.state("L", "F"))
                .hasValueSatisfying(s -> assertThat(s.consecutiveBelow()).isEqualTo(1));

        List<EpisodeChange> changes = machine.apply(tick(3, 280));

        assertThat(changes).hasSize(1);
        assertThat(changes.getFirst().kind()).isEqualTo(EpisodeChange.Kind.OPENED);
        BunchingEpisode episode = changes.getFirst().episode();
        assertThat(episode.episodeStart()).isEqualTo(t(2));
        assertThat(episode.evaluationCount()).isEqualTo(2);
        assertThat(episode.minGapSeconds()).isEqualTo(280);
        assertThat(episode.lastGapSeconds()).isEqualTo(280);
        assertThat(episode.thresholdSeconds()).isEqualTo(300);
        assertThat(episode.scheduledHeadwaySeconds()).isEqualTo(600);
        assertThat(episode.openStopId()).isEqualTo("S4");
        assertThat(episode.isOpen()).isTrue();
        assertThat(episode.id()).isEqualTo(InsightIds.bunching("R", "L", "F", t(2)));
        assertThat(machine.state("L", "F"))
                .hasValueSatisfying(s -> assertThat(s.openEpisodeId()).isEqualTo(episode.id()));
    }

    @Test
    @DisplayName("AN-BS-02 gaps 290, 310, 290: no episode; the count starts over at t3")
    void aGapAboveTheRatioResetsTheCount() {
        List<EpisodeChange> changes = run(290, 310, 290);

        assertThat(changes).isEmpty();
        PairState state = machine.state("L", "F").orElseThrow();
        assertThat(state.consecutiveBelow()).isEqualTo(1);
        assertThat(state.firstBelowAt()).isEqualTo(t(3));
        assertThat(state.pendingMinGapSeconds()).isEqualTo(290);
    }

    @Test
    @DisplayName("AN-BS-03 gaps 299, 299: opens at t2, episode_start t1, min 299")
    void justBelowTheRatioOpens() {
        List<EpisodeChange> changes = run(299, 299);

        assertThat(kinds(changes)).containsExactly(EpisodeChange.Kind.OPENED);
        assertThat(changes.getFirst().episode().episodeStart()).isEqualTo(t(1));
        assertThat(changes.getFirst().episode().minGapSeconds()).isEqualTo(299);
    }

    @Test
    @DisplayName("AN-BS-04 gaps 300, 290: 300 is not below 300, so only one evaluation counts")
    void theOpenComparisonIsStrict() {
        List<EpisodeChange> changes = run(300, 290);

        assertThat(changes).isEmpty();
        assertThat(machine.state("L", "F").orElseThrow().consecutiveBelow()).isEqualTo(1);
    }

    @Test
    @DisplayName("AN-BS-05 gaps 290, 280, 420, 421: opens at t2, open at t3 (420 is not above 420), closes at t4")
    void closesWhenTheGapIsAboveTheCloseRatio() {
        List<EpisodeChange> changes = run(290, 280, 420, 421);

        assertThat(kinds(changes))
                .containsExactly(EpisodeChange.Kind.OPENED, EpisodeChange.Kind.UPDATED, EpisodeChange.Kind.CLOSED);
        BunchingEpisode closed = changes.getLast().episode();
        assertThat(closed.episodeEnd()).isEqualTo(t(4));
        assertThat(closed.closeReason()).isEqualTo(CloseReason.GAP_RECOVERED);
        assertThat(closed.isOpen()).isFalse();
        assertThat(machine.states()).isEmpty();
        assertThat(machine.openEpisodes()).isEmpty();
    }

    @Test
    @DisplayName("AN-BS-06 gaps 290, 280, 350, 250, 360: still open, min 250, last 360, five evaluations")
    void hysteresisKeepsTheEpisodeOpen() {
        List<EpisodeChange> changes = run(290, 280, 350, 250, 360);

        assertThat(changes).noneMatch(c -> c.kind() == EpisodeChange.Kind.CLOSED);
        BunchingEpisode episode = changes.getLast().episode();
        assertThat(episode.isOpen()).isTrue();
        assertThat(episode.minGapSeconds()).isEqualTo(250);
        assertThat(episode.lastGapSeconds()).isEqualTo(360);
        assertThat(episode.evaluationCount()).isEqualTo(5);
        assertThat(episode.lastEvaluatedAt()).isEqualTo(t(5));
    }

    @Test
    @DisplayName("AN-BS-07 opened at t2, then the follower stops reporting: evaluated to t9, closed at t10 SIGNAL_LOST")
    void signalLost() {
        run(290, 280);
        for (int n = 3; n <= 9; n++) {
            // The follower's last position ages but is still under 120 s, so the pair is evaluated every time.
            assertThat(kinds(machine.apply(tick(n, 280)))).containsExactly(EpisodeChange.Kind.UPDATED);
        }

        List<EpisodeChange> changes =
                machine.apply(new TickEvaluation(t(10), List.of(), Map.of("L", position("L", "TL", t(10))), Map.of()));

        assertThat(changes).hasSize(1);
        BunchingEpisode closed = changes.getFirst().episode();
        assertThat(closed.closeReason()).isEqualTo(CloseReason.SIGNAL_LOST);
        assertThat(closed.episodeEnd()).isEqualTo(t(9));
        assertThat(machine.states()).isEmpty();
    }

    @Test
    @DisplayName(
            "AN-BS-08 open, then the follower overtakes: (L, F) closes PAIR_CHANGED at the previous point, (F, L) starts")
    void overtaking() {
        run(290, 280);

        List<EpisodeChange> changes = machine.apply(new TickEvaluation(
                t(3),
                List.of(eval("F", "L", "TF", 290, 600)),
                active(3, "TL", "TF"),
                Map.of("F", SkipReason.NO_LEADER)));

        assertThat(changes).hasSize(1);
        BunchingEpisode closed = changes.getFirst().episode();
        assertThat(closed.closeReason()).isEqualTo(CloseReason.PAIR_CHANGED);
        assertThat(closed.episodeEnd()).isEqualTo(t(2));
        assertThat(machine.state("L", "F")).isEmpty();
        PairState reverse = machine.state("F", "L").orElseThrow();
        assertThat(reverse.consecutiveBelow()).isEqualTo(1);
        assertThat(reverse.isOpen()).isFalse();
    }

    @Test
    @DisplayName("AN-BS-09 open, then the follower's next stop is S8: closes OUT_OF_ZONE")
    void leavingTheZone() {
        run(290, 280);

        List<EpisodeChange> changes = machine.apply(
                new TickEvaluation(t(3), List.of(), active(3, "TL", "TF"), Map.of("F", SkipReason.LAST_STOPS)));

        assertThat(changes).hasSize(1);
        assertThat(changes.getFirst().episode().closeReason()).isEqualTo(CloseReason.OUT_OF_ZONE);
        assertThat(changes.getFirst().episode().episodeEnd()).isEqualTo(t(2));
    }

    @Test
    @DisplayName("AN-BS-10 open, then the leader starts a new trip: closes PAIR_CHANGED, the new pair counts from 1")
    void newTripOfTheLeader() {
        run(290, 280);

        List<EpisodeChange> changes = machine.apply(
                new TickEvaluation(t(3), List.of(eval("L", "F", "TL2", 290, 600)), active(3, "TL2", "TF"), Map.of()));

        assertThat(changes).hasSize(1);
        assertThat(changes.getFirst().episode().closeReason()).isEqualTo(CloseReason.PAIR_CHANGED);
        PairState fresh = machine.state("L", "F").orElseThrow();
        assertThat(fresh.leaderTrip()).isEqualTo("TL2");
        assertThat(fresh.consecutiveBelow()).isEqualTo(1);
        assertThat(fresh.isOpen()).isFalse();
    }

    @Test
    @DisplayName("AN-BS-11 one evaluation below, then the follower has no leader: the pair state row is deleted")
    void aCountingPairThatIsNotEvaluatedIsDeleted() {
        run(290);
        List<PairState> stored = List.copyOf(machine.states().values());

        machine.apply(new TickEvaluation(t(2), List.of(), active(2, "TL", "TF"), Map.of("F", SkipReason.NO_LEADER)));

        assertThat(machine.states()).isEmpty();
        assertThat(PairStateChanges.between(stored, machine.states().values()).deletes())
                .containsExactlyElementsOf(stored);
    }

    @Test
    @DisplayName("AN-BS-12 opened at H = 600, then H = 1,200 and gap 450: stays open; the threshold stays 300")
    void headwayChangesDoNotMoveTheStoredThreshold() {
        run(290, 280);

        List<EpisodeChange> changes = machine.apply(tick(3, 450, 1200));

        assertThat(kinds(changes)).containsExactly(EpisodeChange.Kind.UPDATED);
        BunchingEpisode episode = changes.getFirst().episode();
        assertThat(episode.thresholdSeconds()).isEqualTo(300);
        assertThat(episode.scheduledHeadwaySeconds()).isEqualTo(600);
        assertThat(episode.isOpen()).isTrue();
    }

    @Test
    @DisplayName("a gap above 0.7 x the new headway closes: the close test uses the headway of the grid point")
    void theCloseTestUsesTheCurrentHeadway() {
        run(290, 280);

        assertThat(kinds(machine.apply(tick(3, 450, 600)))).containsExactly(EpisodeChange.Kind.CLOSED);
    }

    @Test
    @DisplayName("three vehicles bunched are two episodes: a vehicle is follower in one pair and leader in another")
    void threeVehiclesAreTwoEpisodes() {
        for (int n = 1; n <= 2; n++) {
            machine.apply(new TickEvaluation(
                    t(n),
                    List.of(eval("A", "B", "TA", 200, 600), eval("B", "C", "TB", 200, 600)),
                    Map.of(
                            "A", position("A", "TA", t(n)),
                            "B", position("B", "TB", t(n)),
                            "C", position("C", "TC", t(n))),
                    Map.of()));
        }

        assertThat(machine.openEpisodes()).hasSize(2);
        assertThat(machine.states()).hasSize(2);
    }

    @Test
    @DisplayName("restore drops a state row that points at an episode which is not open")
    void restoreDropsADanglingRow() {
        run(290, 280);
        PairState open = machine.state("L", "F").orElseThrow();

        PairStateMachine restored = PairStateMachine.restore("R", thresholds(), List.of(open), List.of());

        assertThat(restored.states()).isEmpty();
    }

    @Test
    @DisplayName("a restored machine gives the same result as one that ran all grid points (AN-BS-13 at machine level)")
    void resumingGivesTheSameResult() {
        PairStateMachine whole = new PairStateMachine("R", thresholds());
        List<EpisodeChange> expected = new ArrayList<>();
        int[] gaps = {310, 290, 280, 350, 430};
        for (int i = 0; i < gaps.length; i++) {
            expected.addAll(whole.apply(tick(i + 1, gaps[i])));
        }

        PairStateMachine first = new PairStateMachine("R", thresholds());
        List<EpisodeChange> actual = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            actual.addAll(first.apply(tick(i + 1, gaps[i])));
        }
        PairStateMachine second =
                PairStateMachine.restore("R", thresholds(), first.states().values(), first.openEpisodes());
        for (int i = 3; i < gaps.length; i++) {
            actual.addAll(second.apply(tick(i + 1, gaps[i])));
        }

        assertThat(actual).isEqualTo(expected);
        assertThat(second.states()).isEqualTo(whole.states());
    }

    @Test
    @DisplayName("the same two vehicles in another direction are another pair state row: delete and insert")
    void pairStateChangesFollowThePrimaryKey() {
        run(290);
        PairState before = machine.state("L", "F").orElseThrow();
        PairState moved = new PairState(
                1,
                before.leader(),
                before.follower(),
                before.leaderTrip(),
                before.followerTrip(),
                before.consecutiveBelow(),
                before.firstBelowAt(),
                before.pendingMinGapSeconds(),
                before.pendingStopId(),
                null,
                before.lastEvaluatedAt());

        PairStateChanges changes = PairStateChanges.between(List.of(before), List.of(moved));
        PairStateChanges same = PairStateChanges.between(List.of(before), List.of(before));

        assertThat(changes.deletes()).containsExactly(before);
        assertThat(changes.upserts()).containsExactly(moved);
        assertThat(same.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("an episode that opens and closes within one run is written closed but still reports its opening")
    void theLogKeepsTheOpeningOfAnEpisodeThatClosedInTheSameRun() {
        EpisodeChangeLog log = new EpisodeChangeLog();
        for (int n = 1; n <= 4; n++) {
            log.add(machine.apply(tick(n, new int[] {290, 280, 350, 430}[n - 1])));
        }

        assertThat(log.entries()).hasSize(1);
        EpisodeChangeLog.Entry entry = log.entries().getFirst();
        assertThat(entry.closed()).isTrue();
        assertThat(entry.opened()).isNotNull();
        assertThat(entry.opened().isOpen()).isTrue();
        assertThat(entry.opened().lastGapSeconds()).isEqualTo(280);
        assertThat(entry.latest().closeReason()).isEqualTo(CloseReason.GAP_RECOVERED);
        assertThat(Optional.ofNullable(entry.latest().episodeEnd())).contains(t(4));
    }

    @Test
    @DisplayName("an episode that was open before the run has no opening in the log")
    void anEpisodeOpenBeforeTheRun() {
        run(290, 280);
        BunchingEpisode open = machine.openEpisodes().iterator().next();
        PairStateMachine restored =
                PairStateMachine.restore("R", thresholds(), machine.states().values(), List.of(open));
        EpisodeChangeLog log = new EpisodeChangeLog();

        log.add(restored.apply(tick(3, 270)));

        assertThat(log.entries().getFirst().opened()).isNull();
        assertThat(log.entries().getFirst().closed()).isFalse();
    }
}
