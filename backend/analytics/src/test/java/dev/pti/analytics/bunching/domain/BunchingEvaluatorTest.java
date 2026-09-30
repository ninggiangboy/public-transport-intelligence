package dev.pti.analytics.bunching.domain;

import static dev.pti.analytics.bunching.domain.BunchingFixtures.FakeSchedule;
import static dev.pti.analytics.bunching.domain.BunchingFixtures.at;
import static dev.pti.analytics.bunching.domain.BunchingFixtures.heading;
import static dev.pti.analytics.bunching.domain.BunchingFixtures.position;
import static dev.pti.analytics.bunching.domain.BunchingFixtures.stopped;
import static dev.pti.analytics.bunching.domain.BunchingFixtures.thresholds;
import static dev.pti.analytics.bunching.domain.BunchingFixtures.trip;
import static dev.pti.analytics.bunching.domain.BunchingFixtures.tripWithoutShapeDistance;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The gap of a pair at a grid point, the table of DOC-23 §18.1. Fixture: a trip of ten stops, headway 600 s, grid
 * point {@code T = 12:03:00}; follower {@code F} between {@code S3} and {@code S4}. Where the table lists only the
 * positions that matter, the vehicle also has a recent position so that it counts as active (120 s).
 */
class BunchingEvaluatorTest {

    private static final String T = "12:03:00";

    private static TickEvaluation evaluate(FakeSchedule schedule, VehiclePosition... positions) {
        BunchingEvaluator evaluator = new BunchingEvaluator(thresholds(), schedule);
        return evaluator.evaluate("R", at(T), VehicleTrack.group(Arrays.asList(positions)));
    }

    private static TickEvaluation evaluate(VehiclePosition... positions) {
        return evaluate(FakeSchedule.withHeadway(600, trip()), positions);
    }

    /** The follower F between S3 and S4 at T, heading to S4, 30 s of schedule from the stop. */
    private static VehiclePosition follower() {
        return heading("F", T, 4);
    }

    private static Evaluation onlyEvaluation(TickEvaluation result) {
        assertThat(result.evaluations()).hasSize(1);
        return result.evaluations().getFirst();
    }

    /** A leader that is active but well past the stop under test, so that it does not itself get evaluated. */
    private static VehiclePosition recent(String vehicle) {
        return heading(vehicle, "12:02:55", 7);
    }

    @Test
    @DisplayName("AN-BG-01 leader stopped at S4 at 12:00:00: pass observed, remaining 30 s, gap 210")
    void observedPassage() {
        TickEvaluation result =
                evaluate(heading("L", "11:59:55", 4), stopped("L", "12:00:00", 4), recent("L"), follower());

        Evaluation e = onlyEvaluation(result);
        assertThat(e.leader()).isEqualTo("L");
        assertThat(e.follower()).isEqualTo("F");
        assertThat(e.gapSeconds()).isEqualTo(210);
        assertThat(e.headwaySeconds()).isEqualTo(600);
        assertThat(e.source()).isEqualTo(PassSource.OBSERVED);
        assertThat(e.stopId()).isEqualTo("S4");
        assertThat(e.directionId()).isZero();
        assertThat(e.leaderTrip()).isEqualTo("T1");
        assertThat(e.followerTrip()).isEqualTo("T1");
    }

    @Test
    @DisplayName("AN-BG-02 leader does not stop: pass interpolated between two positions, gap 210")
    void interpolatedPassage() {
        TickEvaluation result = evaluate(
                position("L", "11:59:55", 4, StopStatus.IN_TRANSIT_TO, 1900),
                position("L", "12:00:05", 5, StopStatus.IN_TRANSIT_TO, 2100),
                recent("L"),
                follower());

        Evaluation e = onlyEvaluation(result);
        assertThat(e.gapSeconds()).isEqualTo(210);
        assertThat(e.source()).isEqualTo(PassSource.OBSERVED);
    }

    @Test
    @DisplayName("AN-BG-03 first position of the leader in the look-back is past the stop: pass estimated, gap 150")
    void estimatedPassage() {
        TickEvaluation result = evaluate(stopped("L", "12:02:00", 5), follower());

        Evaluation e = onlyEvaluation(result);
        assertThat(e.gapSeconds()).isEqualTo(150);
        assertThat(e.source()).isEqualTo(PassSource.ESTIMATED);
    }

    @Test
    @DisplayName("AN-BG-04 follower stopped at S4: nothing remaining, gap 180")
    void followerStoppedAtItsNextStop() {
        TickEvaluation result =
                evaluate(heading("L", "11:59:55", 4), stopped("L", "12:00:00", 4), recent("L"), stopped("F", T, 4));

        assertThat(onlyEvaluation(result).gapSeconds()).isEqualTo(180);
    }

    @Test
    @DisplayName("AN-BG-05 follower heading to S1: not evaluated, first_stops")
    void followerInTheFirstStops() {
        TickEvaluation result = evaluate(stopped("L", "12:00:00", 4), recent("L"), heading("F", T, 1));

        assertThat(result.evaluations()).isEmpty();
        assertThat(result.skipped()).containsEntry("F", SkipReason.FIRST_STOPS);
    }

    @Test
    @DisplayName("AN-BG-06 follower heading to S8: not evaluated, last_stops")
    void followerInTheLastStops() {
        TickEvaluation result = evaluate(stopped("L", "12:00:00", 4), recent("L"), heading("F", T, 8));

        assertThat(result.evaluations()).isEmpty();
        assertThat(result.skipped()).containsEntry("F", SkipReason.LAST_STOPS);
    }

    @Test
    @DisplayName("AN-BG-07 the only candidate is at S8, about to finish: no_leader")
    void candidateAboutToFinish() {
        TickEvaluation result = evaluate(stopped("L", "12:00:00", 4), heading("L", "12:02:55", 8), follower());

        assertThat(result.evaluations()).isEmpty();
        assertThat(result.skipped()).containsEntry("F", SkipReason.NO_LEADER);
    }

    @Test
    @DisplayName("AN-BG-08 two candidates passed S4 at 11:50:00 and 12:00:00: the later one leads")
    void theLaterPassageLeads() {
        TickEvaluation result = evaluate(
                stopped("A", "11:50:00", 4), recent("A"), stopped("B", "12:00:00", 4), recent("B"), follower());

        Evaluation e = result.evaluationOfFollower("F").orElseThrow();
        assertThat(e.leader()).isEqualTo("B");
        assertThat(e.gapSeconds()).isEqualTo(210);
    }

    @Test
    @DisplayName("AN-BG-08 a tie goes to the smaller vehicle id")
    void aTieGoesToTheSmallerVehicleId() {
        TickEvaluation result = evaluate(
                stopped("B", "12:00:00", 4), recent("B"), stopped("A", "12:00:00", 4), recent("A"), follower());

        assertThat(result.evaluationOfFollower("F").orElseThrow().leader()).isEqualTo("A");
    }

    @Test
    @DisplayName("AN-BG-09 the candidate's newest position is 125 s old: not active, no_leader")
    void aStalePositionMeansNotRunning() {
        TickEvaluation result = evaluate(stopped("L", "12:00:00", 4), stopped("L", "12:00:55", 4), follower());

        assertThat(result.active()).containsOnlyKeys("F");
        assertThat(result.skipped()).containsEntry("F", SkipReason.NO_LEADER);
    }

    @Test
    @DisplayName("AN-BG-10 no scheduled headway for the hour: no_headway")
    void noHeadway() {
        TickEvaluation result = evaluate(
                new FakeSchedule(OptionalInt.empty(), trip()), stopped("L", "12:00:00", 4), recent("L"), follower());

        assertThat(result.evaluations()).isEmpty();
        assertThat(result.skipped()).containsEntry("F", SkipReason.NO_HEADWAY);
    }

    @Test
    @DisplayName("AN-BG-11 headway of 2,400 s is over max-headway: headway_too_long")
    void headwayTooLong() {
        TickEvaluation result =
                evaluate(FakeSchedule.withHeadway(2400, trip()), stopped("L", "12:00:00", 4), recent("L"), follower());

        assertThat(result.evaluations()).isEmpty();
        assertThat(result.skipped()).containsEntry("F", SkipReason.HEADWAY_TOO_LONG);
    }

    @Test
    @DisplayName("AN-BG-13 stops without shape_dist_traveled, 500 m apart: same result as AN-BG-01 within a second")
    void withoutShapeDistance() {
        TickEvaluation result = evaluate(
                FakeSchedule.withHeadway(600, tripWithoutShapeDistance()),
                heading("L", "11:59:55", 4),
                stopped("L", "12:00:00", 4),
                recent("L"),
                follower());

        assertThat(onlyEvaluation(result).gapSeconds()).isCloseTo(210, within(1));
    }

    @Test
    @DisplayName("AN-BG-14 the leader passed S4 at 11:32:00, before the 30 minute look-back: no_leader")
    void passageBeforeTheLookback() {
        TickEvaluation result = evaluate(stopped("L", "11:33:10", 5), heading("L", "12:02:55", 7), follower());

        assertThat(result.evaluations()).isEmpty();
        assertThat(result.skipped()).containsEntry("F", SkipReason.NO_LEADER);
    }

    @Test
    @DisplayName("AN-BG-15 a candidate running the other direction is not a candidate")
    void otherDirection() {
        TickEvaluation result = evaluate(
                position("L", "12:00:00", 4, StopStatus.STOPPED_AT, 2000, "T1", 1),
                position("L", "12:02:55", 7, StopStatus.IN_TRANSIT_TO, 3250, "T1", 1),
                follower());

        assertThat(result.evaluations()).isEmpty();
        assertThat(result.skipped()).containsEntry("F", SkipReason.NO_LEADER);
    }

    @Test
    @DisplayName("AN-BG-16 a candidate on a short trip that also serves S4 leads; its passage uses its own pattern")
    void shortTripCandidate() {
        // The short trip serves S3 … S8 as sequence 1 … 6; S4 is its second stop, sequence 2.
        var shortTrip = trip("T2", 3, 6);
        TickEvaluation result = evaluate(
                FakeSchedule.withHeadway(600, trip(), shortTrip),
                position("L", "12:00:30", 1, StopStatus.STOPPED_AT, 500, "T2", 0),
                position("L", "12:02:55", 3, StopStatus.IN_TRANSIT_TO, 1250, "T2", 0),
                follower());

        Evaluation e = result.evaluationOfFollower("F").orElseThrow();
        assertThat(e.leader()).isEqualTo("L");
        assertThat(e.leaderTrip()).isEqualTo("T2");
        assertThat(e.gapSeconds()).isEqualTo(180);
    }

    @Test
    @DisplayName("AN-BG-17 the follower's trip is not in the ACTIVE feed: unknown_trip")
    void unknownTrip() {
        TickEvaluation result = evaluate(
                stopped("L", "12:00:00", 4),
                recent("L"),
                position("F", T, 4, StopStatus.IN_TRANSIT_TO, 1750, "no-such-trip", 0));

        assertThat(result.evaluations()).isEmpty();
        assertThat(result.skipped()).containsEntry("F", SkipReason.UNKNOWN_TRIP);
    }

    @Test
    @DisplayName("a stop sequence that the pattern does not have is an unknown trip too")
    void unknownStopSequence() {
        TickEvaluation result = evaluate(new VehiclePosition(
                "F", at(T), BunchingFixtures.DAY, "T1", 0, 44.9, -93.27, 99, StopStatus.IN_TRANSIT_TO));

        assertThat(result.skipped()).containsEntry("F", SkipReason.UNKNOWN_TRIP);
    }

    @Test
    @DisplayName("a vehicle is evaluated on the newest position up to the grid point, not on a later one")
    void positionsAfterTheGridPointAreIgnored() {
        List<VehiclePosition> positions = new ArrayList<>(
                List.of(heading("L", "11:59:55", 4), stopped("L", "12:00:00", 4), recent("L"), follower()));
        positions.add(stopped("F", "12:03:05", 4));

        TickEvaluation result = new BunchingEvaluator(thresholds(), FakeSchedule.withHeadway(600, trip()))
                .evaluate("R", at(T), VehicleTrack.group(positions));

        assertThat(onlyEvaluation(result).gapSeconds()).isEqualTo(210);
    }

    @Test
    @DisplayName("a vehicle that changed trip is judged on the positions of its current trip only")
    void historyFollowsTheCurrentTrip() {
        TickEvaluation result = evaluate(
                // Stopped at S4 on the previous trip T9 at 11:55: that is not a passage on T1.
                position("L", "11:55:00", 4, StopStatus.STOPPED_AT, 2000, "T9", 0),
                heading("L", "12:02:55", 3),
                follower());

        assertThat(result.skipped()).containsEntry("F", SkipReason.NO_LEADER);
    }
}
