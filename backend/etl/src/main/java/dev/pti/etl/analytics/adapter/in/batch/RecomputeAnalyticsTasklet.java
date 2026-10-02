package dev.pti.etl.analytics.adapter.in.batch;

import dev.pti.analytics.core.domain.Detector;
import dev.pti.analytics.recompute.application.AnalyticsRecomputeService;
import dev.pti.analytics.recompute.domain.DetectorStats;
import dev.pti.analytics.recompute.domain.WorkItem;
import dev.pti.common.json.MessageJson;
import dev.pti.etl.batch.StepValues;
import dev.pti.etl.replay.ReplayRequestListener;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import tools.jackson.databind.node.ObjectNode;

/**
 * Step {@code recomputeAnalytics} of {@code RawZoneReplayJob} and step {@code recompute} of
 * {@code AnalyticsRecomputeJob} (DOC-23 §11.5): the first call asks the plan for the work items and keeps them in the
 * step's {@code ExecutionContext}; every call runs one item in the call's own transaction and returns
 * {@code CONTINUABLE} until the last. The items and the position commit with each call, so a restart continues at the
 * next item and the result is that of an uninterrupted run (AN-R-13).
 *
 * <p>When the last item is done the statistics of §11.7 go to the job's context, where the listener of a replay reads
 * them into {@code replay_request.stats.analytics}; the step's exit description has them for a job request.
 */
public class RecomputeAnalyticsTasklet implements Tasklet {

    /** Step context: the work items, one per line. */
    public static final String ITEMS_KEY = "pti.recompute.items";

    /** Step context: how many items are done. */
    public static final String INDEX_KEY = "pti.recompute.index";

    private static final String STATS_PREFIX = "pti.recompute.stats.";

    private static final String LINE = "\n";
    private static final String FIELD = "\t";

    /** Decides what to recompute, from the parameters and the state of the job the step belongs to. */
    @FunctionalInterface
    public interface Plan {

        List<WorkItem> items(StepExecution step);
    }

    private final AnalyticsRecomputeService service;
    private final Plan plan;

    public RecomputeAnalyticsTasklet(AnalyticsRecomputeService service, Plan plan) {
        this.service = service;
        this.plan = plan;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        StepExecution step = contribution.getStepExecution();
        ExecutionContext context = step.getExecutionContext();
        if (!context.containsKey(ITEMS_KEY)) {
            context.putString(ITEMS_KEY, encode(plan.items(step)));
            context.putInt(INDEX_KEY, 0);
        }
        List<WorkItem> items = decode(context.getString(ITEMS_KEY));
        int index = context.getInt(INDEX_KEY);
        if (index < items.size()) {
            DetectorStats stats = service.execute(items.get(index), StepValues.batchId(step));
            add(context, items.get(index).detector(), stats);
            context.putInt(INDEX_KEY, index + 1);
            contribution.incrementWriteCount(stats.upserted());
            index++;
        }
        if (index < items.size()) {
            return RepeatStatus.CONTINUABLE;
        }
        finish(contribution, step, items);
        return RepeatStatus.FINISHED;
    }

    /** DOC-23 §11.7: one entry for every detector that had an item, in the order of {@link Detector}. */
    private static void finish(StepContribution contribution, StepExecution step, List<WorkItem> items) {
        Set<Detector> planned = EnumSet.noneOf(Detector.class);
        items.forEach(item -> planned.add(item.detector()));
        ObjectNode analytics = MessageJson.mapper().createObjectNode();
        StringBuilder summary = new StringBuilder("Recomputed " + items.size() + " items");
        for (Detector detector : planned) {
            DetectorStats stats = stats(step.getExecutionContext(), detector);
            ObjectNode entry = analytics.putObject(detector.name());
            entry.put("scopes", stats.scopes());
            entry.put("upserted", stats.upserted());
            entry.put("deleted", stats.deleted());
            summary.append("; ")
                    .append(detector.name())
                    .append(": ")
                    .append(stats.scopes())
                    .append(" scopes, upserted ")
                    .append(stats.upserted())
                    .append(", deleted ")
                    .append(stats.deleted());
        }
        step.getJobExecution()
                .getExecutionContext()
                .putString(
                        ReplayRequestListener.ANALYTICS_STATS,
                        MessageJson.mapper().writeValueAsString(analytics));
        contribution.setExitStatus(ExitStatus.COMPLETED.addExitDescription(summary.toString()));
    }

    private static void add(ExecutionContext context, Detector detector, DetectorStats stats) {
        DetectorStats total = stats(context, detector).plus(stats);
        context.putInt(STATS_PREFIX + detector + ".scopes", total.scopes());
        context.putInt(STATS_PREFIX + detector + ".upserted", total.upserted());
        context.putInt(STATS_PREFIX + detector + ".deleted", total.deleted());
    }

    private static DetectorStats stats(ExecutionContext context, Detector detector) {
        return new DetectorStats(
                context.getInt(STATS_PREFIX + detector + ".scopes", 0),
                context.getInt(STATS_PREFIX + detector + ".upserted", 0),
                context.getInt(STATS_PREFIX + detector + ".deleted", 0));
    }

    /** One item to a line, its fields separated by a tab: route ids, dates and hours hold neither. */
    static String encode(List<WorkItem> items) {
        List<String> lines = new ArrayList<>();
        for (WorkItem item : items) {
            lines.add(String.join(
                    FIELD,
                    item.detector().name(),
                    item.scope(),
                    item.from().toString(),
                    item.to().toString()));
        }
        return String.join(LINE, lines);
    }

    static List<WorkItem> decode(String encoded) {
        List<WorkItem> items = new ArrayList<>();
        if (encoded.isEmpty()) {
            return items;
        }
        for (String line : encoded.split(LINE)) {
            String[] fields = line.split(FIELD);
            items.add(new WorkItem(
                    Detector.valueOf(fields[0]), fields[1], Instant.parse(fields[2]), Instant.parse(fields[3])));
        }
        return items;
    }
}
