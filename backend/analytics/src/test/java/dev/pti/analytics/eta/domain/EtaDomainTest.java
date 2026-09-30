package dev.pti.analytics.eta.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.analytics.config.AnalyticsPropertiesFixtures;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The window (DOC-23 §7.1) and the confidence levels (§7.3, AN-E-07) of the historical ETA. */
class EtaDomainTest {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private static final EtaSettings SETTINGS =
            AnalyticsPropertiesFixtures.defaults().eta().toSettings();

    @Test
    void theWindowIsTwentyEightDaysBeforeTheHourAndItsBoundsAreOnTheLocalDays() {
        // 21:00Z is 16:00 CDT on Tuesday 29 September.
        EtaWindow window = EtaWindow.of(Instant.parse("2026-09-29T21:00:00Z"), Duration.ofDays(28), CHICAGO);

        assertThat(window.from()).isEqualTo(Instant.parse("2026-09-01T21:00:00Z"));
        assertThat(window.to()).isEqualTo(Instant.parse("2026-09-29T21:00:00Z"));
        assertThat(window.windowStart()).isEqualTo(LocalDate.parse("2026-09-01"));
        assertThat(window.windowEnd()).isEqualTo(LocalDate.parse("2026-09-29"));
        assertThat(window.serviceDates().from())
                .as("one day before the first local date: a trip that started before midnight")
                .isEqualTo(LocalDate.parse("2026-08-31"));
        assertThat(window.serviceDates().to()).isEqualTo(LocalDate.parse("2026-09-29"));
    }

    @Test
    void windowEndIsTheDayOfTheLastInstantNotOfTheHour() {
        // 05:00Z is local midnight: the hour belongs to 30 September, the last second of the window to 29 September.
        EtaWindow window = EtaWindow.of(Instant.parse("2026-09-30T05:00:00Z"), Duration.ofDays(28), CHICAGO);

        assertThat(window.windowEnd()).isEqualTo(LocalDate.parse("2026-09-29"));
        assertThat(window.serviceDates().to()).isEqualTo(LocalDate.parse("2026-09-30"));
        assertThat(window.windowStart()).isEqualTo(LocalDate.parse("2026-09-02"));
    }

    @Test
    void theWindowIsTwentyEightDaysOfSecondsAcrossAClockChange() {
        // Clocks go back on 1 November 2026: the window is 28 × 24 hours, not 28 calendar days.
        EtaWindow window = EtaWindow.of(Instant.parse("2026-11-10T12:00:00Z"), Duration.ofDays(28), CHICAGO);

        assertThat(window.from()).isEqualTo(Instant.parse("2026-10-13T12:00:00Z"));
        assertThat(Duration.between(window.from(), window.to())).isEqualTo(Duration.ofHours(28 * 24));
    }

    @ParameterizedTest(name = "{0} samples are {1}")
    @CsvSource({"0,NONE", "1,LOW", "9,LOW", "10,MEDIUM", "29,MEDIUM", "30,HIGH", "500,HIGH"})
    void anE07ConfidenceFollowsTheSampleCount(int samples, EtaConfidence expected) {
        assertThat(EtaConfidence.of(samples, SETTINGS)).isEqualTo(expected);
    }

    @Test
    void theThresholdsComeFromTheSettings() {
        EtaSettings strict =
                new EtaSettings(Duration.ofDays(28), 20, 100, false, Duration.ofMinutes(2), 10, Duration.ofMinutes(90));

        assertThat(EtaConfidence.of(19, strict)).isEqualTo(EtaConfidence.LOW);
        assertThat(EtaConfidence.of(20, strict)).isEqualTo(EtaConfidence.MEDIUM);
        assertThat(EtaConfidence.of(99, strict)).isEqualTo(EtaConfidence.MEDIUM);
        assertThat(EtaConfidence.of(100, strict)).isEqualTo(EtaConfidence.HIGH);
    }
}
