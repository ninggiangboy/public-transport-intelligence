package dev.pti.analytics.eta.application;

import dev.pti.analytics.core.domain.Trigger;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One route of an ETA aggregation.
 *
 * @param hour the run's hour {@code H}, the same for every route of the run
 * @param batchId the {@code batch_id} written to the rows: the step's (DR-63)
 * @param trigger {@code JOB} for the scheduled or requested job, {@code RECOMPUTE} for a replay
 * @param completion set on the last route of the run: the checkpoint is stored in the same transaction
 */
public record EtaRouteRun(
        String routeId,
        Instant hour,
        UUID batchId,
        Trigger trigger,
        @Nullable Completion completion) {

    /**
     * @param watermark the fingerprint from the plan
     * @param jobExecutionId the execution to record on the checkpoint, when there is one
     */
    public record Completion(String watermark, @Nullable Long jobExecutionId) {}
}
