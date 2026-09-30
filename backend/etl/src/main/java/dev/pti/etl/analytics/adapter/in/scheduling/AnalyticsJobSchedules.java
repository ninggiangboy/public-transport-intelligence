package dev.pti.etl.analytics.adapter.in.scheduling;

import dev.pti.common.time.BusinessClock;
import dev.pti.etl.analytics.adapter.in.batch.EtaAggregationTasklet;
import dev.pti.etl.batch.JobParams;
import dev.pti.etl.batch.PtiJob;
import dev.pti.etl.batch.PtiJobLauncher;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The scheduled triggers of the analytics jobs of {@code etl-batch} (DOC-23 §4.3, DOC-19 §2), each under its ShedLock
 * lock (DR-24 layer 1). A trigger only starts the job. Its {@code runKey} is {@code scheduled:<hour>} or
 * {@code scheduled:<runDate>}, so a cron that fires twice, or two pods, start one instance; an operator's rerun of the
 * same hour or day is a {@code manual:<requestId>} instance.
 */
public class AnalyticsJobSchedules {

    private final PtiJobLauncher launcher;
    private final BusinessClock clock;
    private final ZoneId agencyZone;

    public AnalyticsJobSchedules(PtiJobLauncher launcher, BusinessClock clock, ZoneId agencyZone) {
        this.launcher = launcher;
        this.clock = clock;
        this.agencyZone = agencyZone;
    }

    /** Every hour at :05, real time; the hour of the run is the business hour that has just ended. */
    @Scheduled(cron = "${pti.batch.schedule.eta-aggregation}", zone = "UTC", scheduler = "ptiTaskScheduler")
    @SchedulerLock(name = "etaAggregation", lockAtMostFor = "30m")
    public void etaAggregation() {
        if (launcher.job(PtiJob.ETA_AGGREGATION).isPresent()) {
            launcher.launchIfNew(PtiJob.ETA_AGGREGATION, etaParameters(clock.instant()));
        }
    }

    /** At 03:00 in the agency's zone, real time: the day's trips have ended (DOC-23 §8.2). */
    @Scheduled(
            cron = "${pti.batch.schedule.otp-scorecard}",
            zone = "${pti.gtfs.static.zone}",
            scheduler = "ptiTaskScheduler")
    @SchedulerLock(name = "otpScorecard", lockAtMostFor = "30m")
    public void otpScorecard() {
        if (launcher.job(PtiJob.OTP_SCORECARD).isPresent()) {
            launcher.launchIfNew(PtiJob.OTP_SCORECARD, otpParameters(clock.instant()));
        }
    }

    JobParameters etaParameters(Instant now) {
        String hour = now.truncatedTo(ChronoUnit.HOURS).toString();
        return JobParams.identity(PtiJob.ETA_AGGREGATION, "scheduled:" + hour)
                .addString(EtaAggregationTasklet.HOUR, hour, false)
                .toJobParameters();
    }

    JobParameters otpParameters(Instant now) {
        LocalDate runDate = now.atZone(agencyZone).toLocalDate();
        return JobParams.runKey(PtiJob.OTP_SCORECARD, "scheduled:" + runDate);
    }
}
