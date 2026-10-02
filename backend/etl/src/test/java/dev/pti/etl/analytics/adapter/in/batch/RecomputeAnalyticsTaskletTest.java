package dev.pti.etl.analytics.adapter.in.batch;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.analytics.core.domain.Detector;
import dev.pti.analytics.recompute.application.AnalyticsRecomputeService;
import dev.pti.analytics.recompute.domain.DetectorStats;
import dev.pti.analytics.recompute.domain.ReplayRange;
import dev.pti.analytics.recompute.domain.ReplaySource;
import dev.pti.analytics.recompute.domain.WorkItem;
import dev.pti.common.json.MessageJson;
import dev.pti.etl.batch.StepValues;
import dev.pti.etl.replay.ReplayRequestListener;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.batch.test.MetaDataInstanceFactory;
import tools.jackson.databind.JsonNode;

/** Step {@code recomputeAnalytics} and step {@code recompute} of DOC-23 §11.5: one item per call, then the stats. */
class RecomputeAnalyticsTaskletTest {

    private static final Instant FROM = Instant.parse("2026-09-29T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-29T06:00:00Z");

    private static final List<WorkItem> ITEMS = List.of(
            new WorkItem(Detector.BUNCHING, "18", FROM, TO),
            new WorkItem(Detector.BUNCHING, "22", FROM, TO),
            new WorkItem(Detector.OTP, "2026-09-28", FROM, FROM));

    /** Counts the plans and records every executed item; item {@code failAt} throws once. */
    private static final class FakeService implements AnalyticsRecomputeService {

        int plans;
        int failAt = -1;
        final List<WorkItem> executed = new ArrayList<>();
        final List<UUID> batches = new ArrayList<>();

        @Override
        public List<WorkItem> plan(ReplaySource source, ReplayRange range) {
            plans++;
            return ITEMS;
        }

        @Override
        public List<WorkItem> plan(Set<Detector> detectors, Instant from, Instant to) {
            plans++;
            return ITEMS;
        }

        @Override
        public DetectorStats execute(WorkItem item, UUID batchId) {
            if (executed.size() == failAt) {
                failAt = -1;
                throw new IllegalStateException("database went away");
            }
            executed.add(item);
            batches.add(batchId);
            return new DetectorStats(1, item.scope().length(), item.detector() == Detector.OTP ? 0 : 1);
        }
    }

    private static StepExecution step() {
        JobExecution job = MetaDataInstanceFactory.createJobExecution();
        StepExecution step = MetaDataInstanceFactory.createStepExecution(job, "recompute", 7L);
        step.getExecutionContext()
                .putString(StepValues.BATCH_ID, UUID.randomUUID().toString());
        return step;
    }

    private static List<RepeatStatus> runToEnd(RecomputeAnalyticsTasklet tasklet, StepExecution step) {
        List<RepeatStatus> statuses = new ArrayList<>();
        RepeatStatus status;
        do {
            status = tasklet.execute(new StepContribution(step), null);
            statuses.add(status);
        } while (status.isContinuable());
        return statuses;
    }

    @Test
    void everyCallRunsOneItemAndTheLastPutsTheStatsInTheJobContext() {
        FakeService service = new FakeService();
        StepExecution step = step();
        RecomputeAnalyticsTasklet tasklet =
                new RecomputeAnalyticsTasklet(service, s -> service.plan(EnumSet.allOf(Detector.class), FROM, TO));

        List<RepeatStatus> statuses = runToEnd(tasklet, step);

        assertThat(statuses).containsExactly(RepeatStatus.CONTINUABLE, RepeatStatus.CONTINUABLE, RepeatStatus.FINISHED);
        assertThat(service.plans).as("planned once").isEqualTo(1);
        assertThat(service.executed).isEqualTo(ITEMS);
        assertThat(service.batches).as("the step's batch_id (DR-63)").containsOnly(StepValues.batchId(step));
        JsonNode analytics = MessageJson.mapper()
                .readTree(
                        step.getJobExecution().getExecutionContext().getString(ReplayRequestListener.ANALYTICS_STATS));
        assertThat(analytics.propertyNames()).containsExactly("BUNCHING", "OTP");
        assertThat(analytics.path("BUNCHING").path("scopes").asInt()).isEqualTo(2);
        assertThat(analytics.path("BUNCHING").path("upserted").asInt()).isEqualTo(4);
        assertThat(analytics.path("BUNCHING").path("deleted").asInt()).isEqualTo(2);
        assertThat(analytics.path("OTP").path("scopes").asInt()).isEqualTo(1);
        assertThat(analytics.path("OTP").path("upserted").asInt()).isEqualTo(10);
    }

    @Test
    void anEmptyPlanFinishesAtOnceWithEmptyStats() {
        FakeService service = new FakeService();
        StepExecution step = step();
        RecomputeAnalyticsTasklet tasklet = new RecomputeAnalyticsTasklet(service, s -> List.of());

        assertThat(runToEnd(tasklet, step)).containsExactly(RepeatStatus.FINISHED);

        assertThat(service.executed).isEmpty();
        assertThat(step.getJobExecution().getExecutionContext().getString(ReplayRequestListener.ANALYTICS_STATS))
                .isEqualTo("{}");
    }

    @Test
    void anR13ARestartContinuesAtTheItemThatFailedWithoutPlanningAgain() {
        FakeService service = new FakeService();
        service.failAt = 1;
        StepExecution step = step();
        RecomputeAnalyticsTasklet tasklet =
                new RecomputeAnalyticsTasklet(service, s -> service.plan(EnumSet.allOf(Detector.class), FROM, TO));
        assertThat(tasklet.execute(new StepContribution(step), null)).isEqualTo(RepeatStatus.CONTINUABLE);
        // What the repository committed with the first call; the failed call rolls back and changes nothing.
        String items = step.getExecutionContext().getString(RecomputeAnalyticsTasklet.ITEMS_KEY);
        int index = step.getExecutionContext().getInt(RecomputeAnalyticsTasklet.INDEX_KEY);
        try {
            tasklet.execute(new StepContribution(step), null);
        } catch (IllegalStateException expected) {
            // the item failed
        }

        StepExecution restarted = step();
        restarted.getExecutionContext().putString(RecomputeAnalyticsTasklet.ITEMS_KEY, items);
        restarted.getExecutionContext().putInt(RecomputeAnalyticsTasklet.INDEX_KEY, index);
        runToEnd(tasklet, restarted);

        assertThat(service.plans).isEqualTo(1);
        assertThat(service.executed).as("each item ran once").isEqualTo(ITEMS);
    }

    @Test
    void itemsSurviveTheirTextFormInTheStepContext() {
        assertThat(RecomputeAnalyticsTasklet.decode(RecomputeAnalyticsTasklet.encode(ITEMS)))
                .isEqualTo(ITEMS);
        assertThat(RecomputeAnalyticsTasklet.decode(RecomputeAnalyticsTasklet.encode(List.of())))
                .isEmpty();
    }

    @Test
    void theDetectorsParameterDefaultsToAll() {
        assertThat(RecomputePlans.detectors(null)).isEqualTo(EnumSet.allOf(Detector.class));
        assertThat(RecomputePlans.detectors("OTP+BUNCHING")).containsExactly(Detector.BUNCHING, Detector.OTP);
    }
}
