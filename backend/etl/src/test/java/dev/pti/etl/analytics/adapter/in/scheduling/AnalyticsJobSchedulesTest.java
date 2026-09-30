package dev.pti.etl.analytics.adapter.in.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.common.time.BusinessClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.parameters.JobParameters;

/** What the cron triggers of the analytics jobs put in their job parameters (DOC-23 §4.3). */
class AnalyticsJobSchedulesTest {

    private final AnalyticsJobSchedules schedules = new AnalyticsJobSchedules(
            null, new BusinessClock(Clock.systemUTC(), Duration.ZERO), ZoneId.of("America/Chicago"));

    @Test
    void theEtaTriggerNamesTheHourThatHasJustEndedInBothTheKeyAndTheHourParameter() {
        JobParameters parameters = schedules.etaParameters(Instant.parse("2026-09-29T21:05:13Z"));

        assertThat(parameters.getString("runKey")).isEqualTo("scheduled:2026-09-29T21:00:00Z");
        assertThat(parameters.getString("hour")).isEqualTo("2026-09-29T21:00:00Z");
        assertThat(parameters.getParameter("runKey").identifying()).isTrue();
        assertThat(parameters.getParameter("hour").identifying())
                .as("the key identifies the run; the hour only says what it covers")
                .isFalse();
    }

    @Test
    void theOtpTriggerCarriesTheLocalDateOfTheRunInItsKey() {
        // 08:00Z is 03:00 CDT on the 30th.
        JobParameters parameters = schedules.otpParameters(Instant.parse("2026-09-30T08:00:00Z"));

        assertThat(parameters.getString("runKey")).isEqualTo("scheduled:2026-09-30");
        assertThat(parameters.parameters()).hasSize(1);
    }

    @Test
    void theOtpRunDateIsTheLocalDateNotTheUtcDate() {
        // 02:59Z on the 30th is still the evening of the 29th in Chicago.
        assertThat(schedules
                        .otpParameters(Instant.parse("2026-09-30T02:59:00Z"))
                        .getString("runKey"))
                .isEqualTo("scheduled:2026-09-29");
    }
}
