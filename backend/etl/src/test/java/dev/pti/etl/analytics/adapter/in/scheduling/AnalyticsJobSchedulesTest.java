package dev.pti.etl.analytics.adapter.in.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.common.time.BusinessClock;
import dev.pti.etl.batch.application.JobLaunch;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** What the cron triggers of the analytics jobs put in their job parameters (DOC-23 §4.3). */
class AnalyticsJobSchedulesTest {

    private final AnalyticsJobSchedules schedules = new AnalyticsJobSchedules(
            null, new BusinessClock(Clock.systemUTC(), Duration.ZERO), ZoneId.of("America/Chicago"));

    @Test
    void theEtaTriggerNamesTheHourThatHasJustEndedInBothTheKeyAndTheHourParameter() {
        JobLaunch launch = schedules.etaLaunch(Instant.parse("2026-09-29T21:05:13Z"));

        assertThat(launch.identity()).isEqualTo("scheduled:2026-09-29T21:00:00Z");
        assertThat(launch.parameters()).containsEntry("hour", "2026-09-29T21:00:00Z");
    }

    @Test
    void theOtpTriggerCarriesTheLocalDateOfTheRunInItsKey() {
        // 08:00Z is 03:00 CDT on the 30th.
        JobLaunch launch = schedules.otpLaunch(Instant.parse("2026-09-30T08:00:00Z"));

        assertThat(launch.identity()).isEqualTo("scheduled:2026-09-30");
        assertThat(launch.parameters()).isEmpty();
    }

    @Test
    void theOtpRunDateIsTheLocalDateNotTheUtcDate() {
        // 02:59Z on the 30th is still the evening of the 29th in Chicago.
        assertThat(schedules.otpLaunch(Instant.parse("2026-09-30T02:59:00Z")).identity())
                .isEqualTo("scheduled:2026-09-29");
    }
}
