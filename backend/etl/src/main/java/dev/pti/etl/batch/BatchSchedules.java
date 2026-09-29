package dev.pti.etl.batch;

import dev.pti.common.time.BusinessClock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Every scheduled trigger of {@code etl-batch} (DOC-19 §2, §2.1), each under its ShedLock lock (DR-24 layer 1). The
 * pollers wait until startup has recovered the executions a killed pod left behind (DOC-19 §7.2).
 */
public class BatchSchedules {

    private static final Logger log = LoggerFactory.getLogger(BatchSchedules.class);

    static final String STALE_LOCK = "staleExecutionRecoverer";
    static final Duration STALE_LOCK_AT_MOST = Duration.ofMinutes(5);

    private final PtiJobLauncher launcher;
    private final JobRequestPoller jobRequests;
    private final StaleExecutionRecoverer recoverer;
    private final LockingTaskExecutor locks;
    private final BusinessClock clock;
    private final ZoneId agencyZone;
    private final List<Runnable> startupTasks;
    private final AtomicBoolean ready = new AtomicBoolean();

    /** @param startupTasks run once after the stale executions are recovered, e.g. the first GTFS load */
    public BatchSchedules(
            PtiJobLauncher launcher,
            JobRequestPoller jobRequests,
            StaleExecutionRecoverer recoverer,
            LockingTaskExecutor locks,
            BusinessClock clock,
            ZoneId agencyZone,
            List<? extends Runnable> startupTasks) {
        this.launcher = launcher;
        this.jobRequests = jobRequests;
        this.recoverer = recoverer;
        this.locks = locks;
        this.clock = clock;
        this.agencyZone = agencyZone;
        this.startupTasks = List.copyOf(startupTasks);
    }

    public boolean isReady() {
        return ready.get();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        locks.executeWithLock(
                (Runnable) recoverer::recover,
                new LockConfiguration(clock.realNow(), STALE_LOCK, STALE_LOCK_AT_MOST, Duration.ZERO));
        partitionMaintenance();
        for (Runnable task : startupTasks) {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.error("Startup task failed", e);
            }
        }
        ready.set(true);
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000, scheduler = "ptiTaskScheduler")
    @SchedulerLock(name = STALE_LOCK, lockAtMostFor = "5m")
    public void recoverStaleExecutions() {
        if (ready.get()) {
            recoverer.recover();
        }
    }

    @Scheduled(
            fixedDelayString = "${pti.batch.poller.interval}",
            initialDelayString = "${pti.batch.poller.interval}",
            scheduler = "ptiTaskScheduler")
    @SchedulerLock(name = "jobRequestPoller", lockAtMostFor = "1m")
    public void pollJobRequests() {
        if (ready.get()) {
            jobRequests.poll();
        }
    }

    /** Partitions follow the business date of the agency, so {@code PTI_CLOCK_OFFSET} moves them (test L-01). */
    @Scheduled(cron = "${pti.batch.schedule.partition-maintenance}", zone = "UTC", scheduler = "ptiTaskScheduler")
    @SchedulerLock(name = "partitionMaintenance", lockAtMostFor = "30m")
    public void partitionMaintenance() {
        launcher.launchIfNew(
                PtiJob.PARTITION_MAINTENANCE,
                JobParams.runDate(PtiJob.PARTITION_MAINTENANCE, LocalDate.ofInstant(clock.instant(), agencyZone)));
    }

    @Scheduled(cron = "${pti.batch.schedule.ops-retention}", zone = "UTC", scheduler = "ptiTaskScheduler")
    @SchedulerLock(name = "opsRetention", lockAtMostFor = "1h")
    public void opsRetention() {
        launcher.launchIfNew(PtiJob.OPS_RETENTION, JobParams.runDate(PtiJob.OPS_RETENTION, realToday()));
    }

    @Scheduled(cron = "${pti.batch.schedule.batch-metadata-cleanup}", zone = "UTC", scheduler = "ptiTaskScheduler")
    @SchedulerLock(name = "batchMetadataCleanup", lockAtMostFor = "1h")
    public void batchMetadataCleanup() {
        launcher.launchIfNew(
                PtiJob.BATCH_METADATA_CLEANUP, JobParams.runDate(PtiJob.BATCH_METADATA_CLEANUP, realToday()));
    }

    @Scheduled(cron = "${pti.batch.schedule.dedup-registry-cleanup}", zone = "UTC", scheduler = "ptiTaskScheduler")
    @SchedulerLock(name = "dedupCleanup", lockAtMostFor = "10m")
    public void dedupRegistryCleanup() {
        launcher.launchIfNew(
                PtiJob.DEDUP_REGISTRY_CLEANUP, JobParams.slot(PtiJob.DEDUP_REGISTRY_CLEANUP, clock.realNow(), 15));
    }

    @Scheduled(cron = "${pti.batch.schedule.data-quality}", zone = "UTC", scheduler = "ptiTaskScheduler")
    @SchedulerLock(name = "dataQuality", lockAtMostFor = "4m")
    public void dataQuality() {
        if (launcher.job(PtiJob.DATA_QUALITY).isPresent()) {
            launcher.launchIfNew(PtiJob.DATA_QUALITY, JobParams.slot(PtiJob.DATA_QUALITY, clock.realNow(), 5));
        }
    }

    private LocalDate realToday() {
        return LocalDate.ofInstant(clock.realNow(), ZoneOffset.UTC);
    }
}
