package dev.pti.analytics.core.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.analytics.event.domain.InsightEvent;
import dev.pti.common.events.Audience;
import dev.pti.common.events.UiChannel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RunResultTest {

    private static final UUID BATCH = UUID.fromString("0198a3c4-0000-7000-8000-000000000001");

    @Test
    void theShortcutsCarryTheOutcomeAndNoEvents() {
        RunResult noop = RunResult.noop(Detector.BUNCHING, "18", Trigger.BATCH, BATCH);
        RunResult locked = RunResult.skippedLocked(Detector.DISRUPTION, "18", Trigger.TICK, BATCH);
        RunResult failed = RunResult.error(Detector.BUNCHING, "18", Trigger.TICK, BATCH);

        assertThat(noop.outcome()).isEqualTo(Outcome.NOOP);
        assertThat(locked.outcome()).isEqualTo(Outcome.SKIPPED_LOCKED);
        assertThat(failed.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(List.of(noop, locked, failed)).allSatisfy(r -> {
            assertThat(r.events()).isEmpty();
            assertThat(r.changedAnything()).isFalse();
            assertThat(r.batchId()).isEqualTo(BATCH);
        });
    }

    @Test
    void aRunThatOpenedOrClosedSomethingChangedIt() {
        assertThat(new RunResult(Detector.BUNCHING, "18", Trigger.BATCH, Outcome.OK, BATCH, 4, 1, 0, 0, 0, List.of())
                        .changedAnything())
                .isTrue();
        assertThat(new RunResult(Detector.BUNCHING, "18", Trigger.BATCH, Outcome.OK, BATCH, 4, 0, 0, 0, 2, List.of())
                        .changedAnything())
                .isTrue();
        assertThat(new RunResult(Detector.BUNCHING, "18", Trigger.BATCH, Outcome.OK, BATCH, 4, 0, 0, 0, 0, List.of())
                        .changedAnything())
                .isFalse();
    }

    @Test
    void rewritingAnEpisodeThatStaysOpenIsNotAChange() {
        assertThat(new RunResult(Detector.BUNCHING, "18", Trigger.TICK, Outcome.OK, BATCH, 1, 0, 3, 0, 0, List.of())
                        .changedAnything())
                .isFalse();
    }

    @Test
    void theEventListIsACopy() {
        List<InsightEvent> events = new ArrayList<>();
        events.add(event());
        RunResult result =
                new RunResult(Detector.BUNCHING, "18", Trigger.BATCH, Outcome.OK, BATCH, 1, 1, 0, 0, 0, events);

        events.clear();

        assertThat(result.events()).hasSize(1);
    }

    @Test
    void theTagsAreLowercaseForTheMetricLabels() {
        assertThat(Detector.BUNCHING.tag()).isEqualTo("bunching");
        assertThat(Trigger.RECOMPUTE.tag()).isEqualTo("recompute");
        assertThat(Outcome.SKIPPED_LOCKED.tag()).isEqualTo("skipped_locked");
    }

    @Test
    void batchIdsAreTimeOrderedUuids() {
        UUID first = RunResult.newBatchId();
        UUID second = RunResult.newBatchId();

        assertThat(first.version()).isEqualTo(7);
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void anUntriggeredContextHasNoSourceBatch() {
        RunContext context = RunContext.untriggered(BATCH);

        assertThat(context.sourceBatchId()).isNull();
        assertThat(context.minEventTs()).isNull();
        assertThat(context.sourceRecordTs()).isNull();
        assertThat(context.committedAt()).isNull();
    }

    private static InsightEvent event() {
        Map<String, Object> data = new HashMap<>();
        data.put("id", "e1");
        data.put("closeReason", null);
        return new InsightEvent(
                "bunching.opened",
                UiChannel.ALERTS,
                Audience.OPERATIONS,
                "e1",
                "18",
                Instant.parse("2026-09-29T21:19:30Z"),
                null,
                data);
    }
}
