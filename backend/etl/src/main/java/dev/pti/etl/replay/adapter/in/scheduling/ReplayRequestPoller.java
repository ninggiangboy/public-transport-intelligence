package dev.pti.etl.replay.adapter.in.scheduling;

import dev.pti.etl.batch.application.JobLaunch;
import dev.pti.etl.batch.application.port.BatchReadiness;
import dev.pti.etl.batch.application.port.LaunchJob;
import dev.pti.etl.batch.domain.PtiJob;
import dev.pti.etl.replay.application.port.ReplayRequestStore;
import dev.pti.etl.replay.domain.ReplayRequest;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Starts {@code DlqReplayJob} and {@code RawZoneReplayJob} for PENDING {@code replay_request} rows (DOC-22 §6), at
 * most {@code maxClaims} per tick and only while the job executor has room.
 */
public class ReplayRequestPoller {

    private static final Logger log = LoggerFactory.getLogger(ReplayRequestPoller.class);

    static final Duration INTERRUPTED_AFTER = Duration.ofMinutes(2);

    /** Job parameter of {@code RawZoneReplayJob}: {@code true} runs step {@code recomputeAnalytics} (DOC-22 §4.4). */
    public static final String RECOMPUTE_ANALYTICS = "recomputeAnalytics";

    private final ReplayRequestStore requests;
    private final LaunchJob launcher;
    private final BatchReadiness schedules;
    private final BooleanSupplier executorHasRoom;
    private final int maxClaims;

    public ReplayRequestPoller(
            ReplayRequestStore requests,
            LaunchJob launcher,
            BatchReadiness schedules,
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
        PtiJob job = request.kind() == ReplayRequest.Kind.DLQ_RECORD ? PtiJob.DLQ_REPLAY : PtiJob.RAW_ZONE_REPLAY;
        try {
            LaunchJob.Start started = launcher.start(job, parameters(job, request));
            if (started.executionId() == null) {
                requests.fail(
                        request.id(),
                        started.requeued()
                                ? "The job executor is full; submit the replay again"
                                : "Could not start " + job.jobName() + ": " + started.refusal());
            }
        } catch (RuntimeException e) {
            log.warn("Cannot start {} for replay request {}: {}", job.jobName(), request.id(), e.toString());
            requests.fail(request.id(), "Could not start " + job.jobName() + ": " + e.getMessage());
        }
    }

    public static JobLaunch parameters(PtiJob job, ReplayRequest request) {
        JobLaunch launch = new JobLaunch(request.id().toString(), Map.of()).with("replay", "true");
        if (request.kind() == ReplayRequest.Kind.RAW_RANGE) {
            launch = launch.with("source", request.source().name())
                    .with("fromTs", Objects.requireNonNull(request.fromTs()).toString())
                    .with("toTs", Objects.requireNonNull(request.toTs()).toString())
                    .with(RECOMPUTE_ANALYTICS, Boolean.toString(request.recomputeAnalytics()));
        }
        return launch;
    }
}
