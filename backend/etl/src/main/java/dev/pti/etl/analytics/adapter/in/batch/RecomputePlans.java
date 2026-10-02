package dev.pti.etl.analytics.adapter.in.batch;

import dev.pti.analytics.core.domain.Detector;
import dev.pti.analytics.recompute.application.AnalyticsRecomputeService;
import dev.pti.analytics.recompute.domain.ReplayRange;
import dev.pti.analytics.recompute.domain.ReplaySource;
import dev.pti.etl.replay.ReplayChunkWriter;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;

/**
 * The two plans of {@link RecomputeAnalyticsTasklet} (DOC-23 §11.5): that of a raw zone replay, from the range of the
 * records step {@code replayRecords} wrote, and that of {@code AnalyticsRecomputeJob}, from its parameters.
 */
public final class RecomputePlans {

    /** {@code AnalyticsRecomputeJob}: the detectors joined by {@code +}; all of them when absent. */
    public static final String DETECTORS = "detectors";

    /** {@code AnalyticsRecomputeJob}: start of the range of event time, an ISO-8601 instant. */
    public static final String FROM_TS = "fromTs";

    /** {@code AnalyticsRecomputeJob}: end of the range of event time, an ISO-8601 instant. */
    public static final String TO_TS = "toTs";

    static final String REPLAY_STEP = "replayRecords";

    private RecomputePlans() {}

    /**
     * Plan of step {@code recomputeAnalytics}: the source of the replay and the event-time range of what it wrote. A
     * replay that wrote nothing has nothing to recompute. After a restart step {@code replayRecords} belongs to the
     * failed execution, so its context comes from the repository.
     */
    public static RecomputeAnalyticsTasklet.Plan replay(AnalyticsRecomputeService service, JobRepository repository) {
        return step -> {
            ReplaySource source = ReplaySource.valueOf(step.getJobParameters().getString("source"));
            ExecutionContext replayed = replayRecords(step, repository);
            String min = replayed == null ? "" : replayed.getString(ReplayChunkWriter.MIN_EVENT_TS, "");
            String max = replayed == null ? "" : replayed.getString(ReplayChunkWriter.MAX_EVENT_TS, "");
            if (min.isEmpty() || max.isEmpty()) {
                return List.of();
            }
            // created_at of ticketing sales is not kept yet: ticketing has no recompute before P6-05.
            return service.plan(source, new ReplayRange(Instant.parse(min), Instant.parse(max), null, null));
        };
    }

    /** Plan of step {@code recompute} of {@code AnalyticsRecomputeJob}: its detectors over {@code [fromTs, toTs]}. */
    public static RecomputeAnalyticsTasklet.Plan job(AnalyticsRecomputeService service) {
        return step -> {
            JobParameters parameters = step.getJobParameters();
            return service.plan(
                    detectors(parameters.getString(DETECTORS)),
                    Instant.parse(String.valueOf(parameters.getString(FROM_TS))),
                    Instant.parse(String.valueOf(parameters.getString(TO_TS))));
        };
    }

    /** {@code BUNCHING+DISRUPTION} to its detectors; {@code null} or blank to all of them (DOC-23 §11.5). */
    public static Set<Detector> detectors(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return EnumSet.allOf(Detector.class);
        }
        Set<Detector> detectors = EnumSet.noneOf(Detector.class);
        Arrays.stream(value.split("\\+"))
                .map(String::trim)
                .map(Detector::valueOf)
                .forEach(detectors::add);
        return detectors;
    }

    private static @Nullable ExecutionContext replayRecords(StepExecution step, JobRepository repository) {
        for (StepExecution other : step.getJobExecution().getStepExecutions()) {
            if (other.getStepName().equals(REPLAY_STEP)) {
                return other.getExecutionContext();
            }
        }
        StepExecution last =
                repository.getLastStepExecution(step.getJobExecution().getJobInstance(), REPLAY_STEP);
        return last == null ? null : last.getExecutionContext();
    }
}
