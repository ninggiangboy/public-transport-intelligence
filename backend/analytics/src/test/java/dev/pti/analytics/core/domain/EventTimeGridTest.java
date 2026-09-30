package dev.pti.analytics.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The grid, watermark and catch-up arithmetic of DOC-23 §2.2. */
class EventTimeGridTest {

    private static final Duration STEP = Duration.ofSeconds(15);

    private static Instant at(String time) {
        return Instant.parse("2026-09-29T" + time + "Z");
    }

    @Test
    void floorGridRoundsDownToAMultipleOfTheIntervalFromTheEpoch() {
        assertThat(EventTimeGrid.floorGrid(at("21:19:37.500"), STEP)).isEqualTo(at("21:19:30"));
        assertThat(EventTimeGrid.floorGrid(at("21:19:30"), STEP)).isEqualTo(at("21:19:30"));
        assertThat(EventTimeGrid.floorGrid(at("21:19:59.999"), STEP)).isEqualTo(at("21:19:45"));
        assertThat(EventTimeGrid.floorGrid(at("21:19:37"), Duration.ofMinutes(1)))
                .isEqualTo(at("21:19:00"));
    }

    @Test
    void floorGridBeforeTheEpochStillRoundsDown() {
        Instant before = Instant.parse("1969-12-31T23:59:50Z");

        assertThat(EventTimeGrid.floorGrid(before, STEP)).isEqualTo(Instant.parse("1969-12-31T23:59:45Z"));
    }

    @Test
    void ceilGridRoundsUpAndKeepsAGridPoint() {
        assertThat(EventTimeGrid.ceilGrid(at("21:19:30.001"), STEP)).isEqualTo(at("21:19:45"));
        assertThat(EventTimeGrid.ceilGrid(at("21:19:30"), STEP)).isEqualTo(at("21:19:30"));
        assertThat(EventTimeGrid.ceilGrid(at("21:19:46"), STEP)).isEqualTo(at("21:20:00"));
    }

    @Test
    void anIntervalThatIsNotAPositiveWholeNumberOfSecondsIsRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> EventTimeGrid.floorGrid(at("21:19:30"), Duration.ZERO));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> EventTimeGrid.floorGrid(at("21:19:30"), Duration.ofMillis(1500)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> EventTimeGrid.floorGrid(at("21:19:30"), Duration.ofSeconds(-15)));
    }

    @Test
    void theWatermarkTrailsTheNewestDataByTheAllowedLateness() {
        Instant watermark =
                EventTimeGrid.watermark(at("21:20:00"), at("21:19:58"), Duration.ofSeconds(5), Duration.ofSeconds(60));

        assertThat(watermark).isEqualTo(at("21:19:53"));
    }

    @Test
    void aRouteThatWentQuietFallsBackToTheIdleBranch() {
        Instant watermark =
                EventTimeGrid.watermark(at("21:20:00"), at("21:10:00"), Duration.ofSeconds(5), Duration.ofSeconds(60));

        assertThat(watermark).isEqualTo(at("21:19:00"));
    }

    @Test
    void aRouteWithoutDataHasOnlyTheIdleBranch() {
        assertThat(EventTimeGrid.watermark(at("21:20:00"), null, Duration.ofSeconds(5), Duration.ofSeconds(60)))
                .isEqualTo(at("21:19:00"));
    }

    @Test
    void theWatermarkNeverPassesNow() {
        Instant watermark =
                EventTimeGrid.watermark(at("21:20:00"), at("21:20:10"), Duration.ofSeconds(5), Duration.ofSeconds(60));

        assertThat(watermark).isEqualTo(at("21:20:00"));
    }

    @Test
    void theWatermarkIsTheLaterOfTheTwoBranchesWhenBothAreBehindNow() {
        // Data is 30 s old with 5 s lateness (21:19:25) while the idle branch is at 21:19:00: the data branch wins.
        assertThat(EventTimeGrid.watermark(
                        at("21:20:00"), at("21:19:30"), Duration.ofSeconds(5), Duration.ofSeconds(60)))
                .isEqualTo(at("21:19:25"));
    }

    @Test
    void aNewRouteStartsOneStepBeforeTheGridPointOfTheFirstBatch() {
        assertThat(EventTimeGrid.initialCursor(at("21:19:37"), at("21:20:00"), STEP))
                .isEqualTo(at("21:19:15"));
        assertThat(EventTimeGrid.initialCursor(null, at("21:20:00"), STEP)).isEqualTo(at("21:19:45"));
    }

    @Test
    void pointsAfterTheCursorUpToTheLastGridPointAreListedOldestFirst() {
        assertThat(EventTimeGrid.pointsAfter(at("21:19:30"), at("21:20:00"), STEP))
                .containsExactly(at("21:19:45"), at("21:20:00"));
        assertThat(EventTimeGrid.pointsAfter(at("21:19:31"), at("21:20:00"), STEP))
                .containsExactly(at("21:19:45"), at("21:20:00"));
        assertThat(EventTimeGrid.pointsAfter(at("21:20:00"), at("21:20:00"), STEP))
                .isEmpty();
        assertThat(EventTimeGrid.pointsAfter(at("21:20:00"), at("21:19:00"), STEP))
                .isEmpty();
    }

    @Test
    void aRouteWithinTheCatchUpLimitKeepsItsCursor() {
        EventTimeGrid.CatchUp result =
                EventTimeGrid.limitCatchUp(at("21:10:00"), at("21:20:07"), Duration.ofMinutes(15), STEP);

        assertThat(result.cursor()).isEqualTo(at("21:10:00"));
        assertThat(result.skippedPoints()).isZero();
    }

    @Test
    void aRouteBeyondTheCatchUpLimitJumpsAheadAndCountsTheSkippedPoints() {
        EventTimeGrid.CatchUp result =
                EventTimeGrid.limitCatchUp(at("20:00:00"), at("21:20:07"), Duration.ofMinutes(15), STEP);

        assertThat(result.cursor()).isEqualTo(at("21:05:00"));
        assertThat(result.skippedPoints()).isEqualTo(65 * 4);
    }

    @Test
    void theLimitIsInclusive() {
        // last = 21:20:00, cursor = 21:05:00: exactly the limit, nothing is skipped.
        EventTimeGrid.CatchUp result =
                EventTimeGrid.limitCatchUp(at("21:05:00"), at("21:20:07"), Duration.ofMinutes(15), STEP);

        assertThat(result.skippedPoints()).isZero();
        assertThat(result.cursor()).isEqualTo(at("21:05:00"));
    }
}
