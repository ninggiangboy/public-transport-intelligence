package dev.pti.etl.replay;

import dev.pti.etl.batch.BatchSchedules;
import dev.pti.etl.batch.JobParams;
import dev.pti.etl.batch.PtiJob;
import dev.pti.etl.batch.PtiJobLauncher;
import dev.pti.etl.replay.ReplayRequests.ReplayRequest;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Starts {@code DlqReplayJob} and {@code RawZoneReplayJob} for PENDING {@code replay_request} rows (DOC-22 §6), at
 * most {@code maxClaims} per tick and only while the job executor has room.
 */
public class ReplayRequestPoller {

    private static final Logger log = LoggerFactory.getLogger(ReplayRequestPoller.class);

    static final Duration INTERRUPTED_AFTER = Duration.ofMinutes(2);

    private final ReplayRequests requests;
    private final PtiJobLauncher launcher;
    private final BatchSchedules schedules;
    private final BooleanSupplier executorHasRoom;
    private final int maxClaims;

    public ReplayRequestPoller(
            ReplayRequests requests,
            PtiJobLauncher launcher,
            BatchSchedules schedules,
            BooleanSupplier executorHasRoom,
            int maxClaims) {
        this.requests = requests;
        this.launcher = launcher;
        this.schedules = schedules;
        this.executorHasRoom = executorHasRoom;
        this.maxClaims = maxClaims;
    }

    @Scheduled(
            fixedDelayString = "${pti.replay.poller.interval}",
            initialDelayString = "${pti.replay.poller.interval}",
            scheduler = "ptiTaskScheduler")
    @SchedulerLock(name = "replayRequestPoller", lockAtMostFor = "1m")
    public void scheduled() {
        if (schedules.isReady()) {
            poll();
        }
    }

    public void poll() {
        requests.failInterrupted(INTERRUPTED_AFTER);
        for (int i = 0; i < maxClaims && executorHasRoom.getAsBoolean(); i++) {
            Optional<ReplayRequest> claimed = requests.claim();
            if (claimed.isEmpty()) {
                return;
            }
            start(claimed.get());
        }
    }

    void start(ReplayRequest request) {
        PtiJob job = request.kind() == ReplayRequests.Kind.DLQ_RECORD ? PtiJob.DLQ_REPLAY : PtiJob.RAW_ZONE_REPLAY;
        if (request.recomputeAnalytics()) {
            requests.fail(request.id(), "recomputeAnalytics is not available before the analytics phase (P4)");
            return;
        }
        try {
            launcher.start(job, parameters(job, request));
        } catch (TaskRejectedException e) {
            requests.fail(request.id(), "The job executor is full; submit the replay again");
        } catch (Exception e) {
            log.warn("Cannot start {} for replay request {}: {}", job.jobName(), request.id(), e.toString());
            requests.fail(request.id(), "Could not start " + job.jobName() + ": " + e.getMessage());
        }
    }

    static JobParameters parameters(PtiJob job, ReplayRequest request) {
        var builder = JobParams.identity(job, request.id().toString()).addString("replay", "true", false);
        if (request.kind() == ReplayRequests.Kind.RAW_RANGE) {
            builder.addString("source", request.source().name(), false)
                    .addString(
                            "fromTs", Objects.requireNonNull(request.fromTs()).toString(), false)
                    .addString("toTs", Objects.requireNonNull(request.toTs()).toString(), false)
                    .addString("recomputeAnalytics", "false", false);
        }
        return builder.toJobParameters();
    }
}
