package dev.pti.etl.analytics.adapter.in.batch;

import static dev.pti.etl.analytics.adapter.in.batch.EtaAggregationTasklet.NOOP;

import dev.pti.analytics.core.domain.RunResult;
import dev.pti.analytics.core.domain.Trigger;
import dev.pti.analytics.otp.application.OtpDayRun;
import dev.pti.analytics.otp.application.OtpPlan;
import dev.pti.analytics.otp.application.OtpPlanRequest;
import dev.pti.analytics.otp.application.OtpRunPlanner;
import dev.pti.analytics.otp.application.OtpScorecardCalculator;
import dev.pti.analytics.otp.domain.OtpServiceDates;
import dev.pti.etl.batch.StepValues;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

/**
 * Step {@code computeOtp} of {@code OtpScorecardJob} (DOC-23 §8.2): the first call asks the planner which service dates
 * to score, and every call scores one date in the call's own transaction, returning {@code CONTINUABLE} until the last.
 * The dates and the position live in the step's {@code ExecutionContext}, so a restart continues at the next date.
 *
 * <p>The dates come from the {@code serviceDates} parameter of a request; without it they are the days before the
 * {@code runDate} that the nightly {@code runKey = scheduled:<runDate>} carries, or before today for a manual run.
 */
public class OtpScorecardTasklet implements Tasklet {

    /** Job parameter: the dates to score, ISO dates separated by {@code +} or commas. */
    public static final String SERVICE_DATES = "serviceDates";

    static final String RUN_KEY = "runKey";
    static final String SCHEDULED = "scheduled:";

    static final String DATES_KEY = "pti.otp.dates";
    static final String INDEX_KEY = "pti.otp.index";
    static final String UPSERTED_KEY = "pti.otp.upserted";
    static final String DELETED_KEY = "pti.otp.deleted";

    private static final String SEPARATOR = ",";

    private final OtpRunPlanner planner;
    private final OtpScorecardCalculator calculator;

    public OtpScorecardTasklet(OtpRunPlanner planner, OtpScorecardCalculator calculator) {
        this.planner = planner;
        this.calculator = calculator;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        StepExecution step = contribution.getStepExecution();
        ExecutionContext context = step.getExecutionContext();
        if (!context.containsKey(DATES_KEY)) {
            OtpPlan plan = planner.plan(request(step));
            if (plan.status() == OtpPlan.Status.NO_FEED) {
                contribution.setExitStatus(new ExitStatus(NOOP, "No feed is ACTIVE"));
                return RepeatStatus.FINISHED;
            }
            context.putString(
                    DATES_KEY,
                    String.join(
                            SEPARATOR,
                            plan.serviceDates().stream()
                                    .map(LocalDate::toString)
                                    .toList()));
            context.putInt(INDEX_KEY, 0);
        }
        List<LocalDate> dates = dates(context);
        int index = context.getInt(INDEX_KEY);
        if (index >= dates.size()) {
            contribution.setExitStatus(completed(context, dates));
            return RepeatStatus.FINISHED;
        }
        RunResult result = calculator.calculate(new OtpDayRun(dates.get(index), StepValues.batchId(step), Trigger.JOB));
        context.putInt(INDEX_KEY, index + 1);
        context.putLong(UPSERTED_KEY, context.getLong(UPSERTED_KEY, 0L) + result.updated());
        context.putLong(DELETED_KEY, context.getLong(DELETED_KEY, 0L) + result.deleted());
        contribution.incrementWriteCount(result.updated());
        if (index == dates.size() - 1) {
            contribution.setExitStatus(completed(context, dates));
            return RepeatStatus.FINISHED;
        }
        return RepeatStatus.CONTINUABLE;
    }

    private static OtpPlanRequest request(StepExecution step) {
        String requested = step.getJobParameters().getString(SERVICE_DATES);
        List<LocalDate> dates = requested == null || requested.isBlank() ? null : OtpServiceDates.parse(requested);
        return new OtpPlanRequest(dates, runDate(step.getJobParameters().getString(RUN_KEY)));
    }

    /** The date of {@code scheduled:<runDate>}; {@code null} for any other key, which means today. */
    static @Nullable LocalDate runDate(@Nullable String runKey) {
        if (runKey == null || !runKey.startsWith(SCHEDULED)) {
            return null;
        }
        try {
            return LocalDate.parse(runKey.substring(SCHEDULED.length()));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static List<LocalDate> dates(ExecutionContext context) {
        String joined = context.getString(DATES_KEY);
        return joined.isEmpty()
                ? List.of()
                : Arrays.stream(joined.split(SEPARATOR)).map(LocalDate::parse).toList();
    }

    private static ExitStatus completed(ExecutionContext context, List<LocalDate> dates) {
        return ExitStatus.COMPLETED.addExitDescription("Scored " + dates.size() + " service dates "
                + context.getString(DATES_KEY, "") + ": upserted " + context.getLong(UPSERTED_KEY, 0L) + ", deleted "
                + context.getLong(DELETED_KEY, 0L));
    }
}
