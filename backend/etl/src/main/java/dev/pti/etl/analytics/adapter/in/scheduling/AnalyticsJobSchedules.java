package dev.pti.etl.analytics.adapter.in.scheduling;

import dev.pti.common.time.BusinessClock;
import dev.pti.etl.analytics.adapter.in.batch.EtaAggregationTasklet;
import dev.pti.etl.batch.application.JobLaunch;
import dev.pti.etl.batch.application.port.LaunchJob;
import dev.pti.etl.batch.domain.PtiJob;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The scheduled triggers of the analytics jobs of {@code etl-batch} (DOC-23 §4.3, DOC-19 §2), each under its ShedLock
 * lock (DR-24 layer 1). A trigger only starts the job. Its {@code runKey} is {@code scheduled:<hour>} or
 * {@code scheduled:<runDate>}, so a cron that fires twice, or two pods, start one instance; an operator's rerun of the
 * same hour or day is a {@code manual:<requestId>} instance.
 */
public class AnalyticsJobSchedules {

    private final LaunchJob launcher;
    private final BusinessClock clock;
    private final ZoneId agencyZone;

    public AnalyticsJobSchedules(LaunchJob launcher, BusinessClock clock, ZoneId agencyZone) {
        this.launcher = launcher;
        this.clock = clock;
        this.agencyZone = agencyZone;
    }

    /** Every hour at :05, real time; the hour of the run is the business hour that has just ended. */
    @Scheduled(cron = "${pti.batch.schedule.eta-aggregation}", zone = "UTC", scheduler = "ptiTaskScheduler")
    @SchedulerLock(name = "etaAggregation", lockAtMostFor = "30m")
    public void etaAggregation() {
        if (launcher.isDeployed(PtiJob.ETA_AGGREGATION)) {
            launcher.launchIfNew(PtiJob.ETA_AGGREGATION, etaLaunch(clock.instant()));
        }
    }

    /** At 03:00 in the agency's zone, real time: the day's trips have ended (DOC-23 §8.2). */
    @Scheduled(
            cron = "${pti.batch.schedule.otp-scorecard}",
            zone = "${pti.gtfs.static.zone}",
            scheduler = "ptiTaskScheduler")
    @SchedulerLock(name = "otpScorecard", lockAtMostFor = "30m")
    public void otpScorecard() {
        if (launcher.isDeployed(PtiJob.OTP_SCORECARD)) {
            launcher.launchIfNew(PtiJob.OTP_SCORECARD, otpLaunch(clock.instant()));
        }
    }

    JobLaunch etaLaunch(Instant now) {
        String hour = now.truncatedTo(ChronoUnit.HOURS).toString();
        return new JobLaunch("scheduled:" + hour, Map.of(EtaAggregationTasklet.HOUR, hour));
    }

    JobLaunch otpLaunch(Instant now) {
        LocalDate runDate = now.atZone(agencyZone).toLocalDate();
        return new JobLaunch("scheduled:" + runDate, Map.of());
    }
}
