package dev.pti.etl.analytics.adapter.in.batch;

import dev.pti.analytics.core.domain.RunResult;
import dev.pti.analytics.core.domain.Trigger;
import dev.pti.analytics.eta.application.EtaAggregator;
import dev.pti.analytics.eta.application.EtaPlan;
import dev.pti.analytics.eta.application.EtaPlanRequest;
import dev.pti.analytics.eta.application.EtaRouteRun;
import dev.pti.analytics.eta.application.EtaRunPlanner;
import dev.pti.common.time.BusinessClock;
import dev.pti.etl.batch.StepValues;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

/**
 * Step {@code aggregateEta} of {@code EtaAggregationJob} (DOC-23 §7.2): the first call asks the planner what to do, and
 * every call aggregates one route in the call's own transaction, returning {@code CONTINUABLE} until the last. The
 * plan and the position live in the step's {@code ExecutionContext}, which commits with each route, so a restart
 * continues at the next route and the result is the one of an uninterrupted run (AN-E-11). An hour that is planned
 * once stays that hour on restart, even if the clock has moved on.
 */
public class EtaAggregationTasklet implements Tasklet {

    /** Job parameter: the run's hour {@code H}, an ISO instant on the hour. Default: the hour of the business time. */
    public static final String HOUR = "hour";

    /** Job parameter: {@code true} runs even when no observed arrival is new. */
    public static final String FORCE = "force";

    /** Exit code of a run that had nothing to do (DOC-23 §7.2, AN-E-08). */
    public static final String NOOP = "NOOP";

    static final String ROUTES_KEY = "pti.eta.routes";
    static final String INDEX_KEY = "pti.eta.index";
    static final String WATERMARK_KEY = "pti.eta.watermark";
    static final String HOUR_KEY = "pti.eta.hour";
    static final String UPSERTED_KEY = "pti.eta.upserted";
    static final String DELETED_KEY = "pti.eta.deleted";

    /** Route ids cannot hold a line break, so a list in the context is one string. */
    private static final String SEPARATOR = "\n";

    private final EtaRunPlanner planner;
    private final EtaAggregator aggregator;
    private final BusinessClock clock;

    public EtaAggregationTasklet(EtaRunPlanner planner, EtaAggregator aggregator, BusinessClock clock) {
        this.planner = planner;
        this.aggregator = aggregator;
        this.clock = clock;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        StepExecution step = contribution.getStepExecution();
        ExecutionContext context = step.getExecutionContext();
        if (!context.containsKey(ROUTES_KEY) && !plan(contribution, step, context)) {
            return RepeatStatus.FINISHED;
        }
        List<String> routes = routes(context);
        int index = context.getInt(INDEX_KEY);
        if (index >= routes.size()) {
            contribution.setExitStatus(completed(context, routes.size()));
            return RepeatStatus.FINISHED;
        }
        boolean last = index == routes.size() - 1;
        EtaRouteRun.Completion completion =
                last ? new EtaRouteRun.Completion(context.getString(WATERMARK_KEY), step.getJobExecutionId()) : null;
        RunResult result = aggregator.aggregate(new EtaRouteRun(
                routes.get(index),
                Instant.parse(context.getString(HOUR_KEY)),
                StepValues.batchId(step),
                Trigger.JOB,
                completion));
        context.putInt(INDEX_KEY, index + 1);
        context.putLong(UPSERTED_KEY, context.getLong(UPSERTED_KEY, 0L) + result.updated());
        context.putLong(DELETED_KEY, context.getLong(DELETED_KEY, 0L) + result.deleted());
        contribution.incrementWriteCount(result.updated());
        if (last) {
            contribution.setExitStatus(completed(context, routes.size()));
            return RepeatStatus.FINISHED;
        }
        return RepeatStatus.CONTINUABLE;
    }

    /** @return false when the run has nothing to do and the step's exit status says so */
    private boolean plan(StepContribution contribution, StepExecution step, ExecutionContext context) {
        Instant hour = hour(step);
        boolean force = Boolean.parseBoolean(step.getJobParameters().getString(FORCE));
        EtaPlan plan = planner.plan(new EtaPlanRequest(hour, force));
        switch (plan.status()) {
            case NO_FEED -> {
                contribution.setExitStatus(new ExitStatus(NOOP, "No feed is ACTIVE"));
                return false;
            }
            case UP_TO_DATE -> {
                contribution.setExitStatus(
                        new ExitStatus(NOOP, "No new observed arrival since the last run (" + plan.watermark() + ")"));
                return false;
            }
            case RUN -> {
                context.putString(ROUTES_KEY, String.join(SEPARATOR, plan.routeIds()));
                context.putInt(INDEX_KEY, 0);
                context.putString(WATERMARK_KEY, plan.watermark());
                context.putString(HOUR_KEY, hour.toString());
                return true;
            }
            default -> throw new IllegalStateException("Unexpected plan " + plan.status());
        }
    }

    private Instant hour(StepExecution step) {
        String parameter = step.getJobParameters().getString(HOUR);
        return parameter == null || parameter.isBlank()
                ? clock.instant().truncatedTo(ChronoUnit.HOURS)
                : Instant.parse(parameter);
    }

    private static List<String> routes(ExecutionContext context) {
        String joined = context.getString(ROUTES_KEY);
        return joined.isEmpty() ? List.of() : Arrays.asList(joined.split(SEPARATOR));
    }

    private static ExitStatus completed(ExecutionContext context, int routes) {
        return ExitStatus.COMPLETED.addExitDescription("Aggregated " + routes + " routes for "
                + context.getString(HOUR_KEY, "") + ": upserted " + context.getLong(UPSERTED_KEY, 0L) + ", deleted "
                + context.getLong(DELETED_KEY, 0L));
    }
}
