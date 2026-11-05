package dev.pti.etl.gtfs.adapter.in.scheduling;

import dev.pti.common.time.BusinessClock;
import dev.pti.etl.batch.application.JobLaunch;
import dev.pti.etl.batch.application.port.BatchStartupTask;
import dev.pti.etl.batch.application.port.LaunchJob;
import dev.pti.etl.batch.domain.PtiJob;
import dev.pti.etl.gtfs.adapter.in.batch.FetchFeedTasklet;
import dev.pti.etl.gtfs.application.port.FeedFiles;
import dev.pti.etl.gtfs.application.port.FeedVersionStore;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * What starts {@code GtfsStaticLoadJob} without a job request (DOC-21 §1): the first load at startup when no feed is
 * ACTIVE ({@code GtfsBootstrapRunner}), and the daily load when {@code pti.gtfs.static.source} is set.
 */
public class GtfsTriggers implements BatchStartupTask {

    private static final Logger log = LoggerFactory.getLogger(GtfsTriggers.class);

    /** Workspaces of failed jobs older than this are removed at startup (DOC-21 §5). */
    static final Duration STALE_WORKSPACE = Duration.ofDays(2);

    private final LaunchJob launcher;
    private final FeedVersionStore versions;
    private final JobRepository jobRepository;
    private final FeedFiles workspace;
    private final BusinessClock clock;
    private final ZoneId agencyZone;
    private final String bootstrapLocation;
    private final String dailySource;

    public GtfsTriggers(
            LaunchJob launcher,
            FeedVersionStore versions,
            JobRepository jobRepository,
            FeedFiles workspace,
            BusinessClock clock,
            ZoneId agencyZone,
            String bootstrapLocation,
            String dailySource) {
        this.launcher = launcher;
        this.versions = versions;
        this.jobRepository = jobRepository;
        this.workspace = workspace;
        this.clock = clock;
        this.agencyZone = agencyZone;
        this.bootstrapLocation = bootstrapLocation;
        this.dailySource = dailySource;
        if (dailySource.isEmpty()) {
            log.info("pti.gtfs.static.source is empty: no daily GTFS load is scheduled");
        }
    }

    /** {@code GtfsBootstrapRunner}: runs after the stale executions are recovered. */
    @Override
    public void run() {
        workspace.deleteOlderThan(STALE_WORKSPACE);
        if (versions.activeId().isPresent()) {
            return;
        }
        if (!jobRepository
                .findRunningJobExecutions(PtiJob.GTFS_STATIC_LOAD.jobName())
                .isEmpty()) {
            log.info("No ACTIVE feed yet; a GtfsStaticLoadJob is already running");
            return;
        }
        if (bootstrapLocation.isEmpty()) {
            log.warn("No ACTIVE feed and pti.gtfs.bootstrap-location is empty: GTFS-realtime stays paused (RB-05)");
            return;
        }
        launcher.launchIfNew(PtiJob.GTFS_STATIC_LOAD, parameters("startup", bootstrapLocation));
    }

    @Scheduled(cron = "${pti.gtfs.static.cron}", zone = "${pti.gtfs.static.zone}", scheduler = "ptiTaskScheduler")
    @SchedulerLock(name = "gtfsStaticLoad", lockAtMostFor = "2h")
    public void daily() {
        if (!dailySource.isEmpty()) {
            launcher.launchIfNew(PtiJob.GTFS_STATIC_LOAD, parameters("scheduled", dailySource));
        }
    }

    JobLaunch parameters(String trigger, String sourceUri) {
        LocalDate businessDate = LocalDate.ofInstant(clock.instant(), agencyZone);
        return new JobLaunch(trigger + ":" + businessDate, Map.of()).with(FetchFeedTasklet.SOURCE_URI, sourceUri);
    }
}
