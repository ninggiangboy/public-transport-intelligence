package dev.pti.etl.analytics.adapter.in.batch;

import dev.pti.analytics.otp.domain.OtpServiceDates;
import dev.pti.common.time.BusinessClock;
import dev.pti.etl.batch.JobParameterCheck;
import dev.pti.etl.batch.PtiJob;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Refuses a {@code job_request} for an analytics job whose parameters cannot run (DOC-23 §15): the request ends
 * {@code REJECTED} with the message, before any execution exists. The messages are English, as they are shown to the
 * operator as written.
 *
 * <ul>
 *   <li>{@code EtaAggregationJob}: {@code hour} is an ISO-8601 instant on the hour that is not after the current
 *       hour, {@code force} is {@code true} or {@code false};
 *   <li>{@code OtpScorecardJob}: every date of {@code serviceDates} is an ISO-8601 date before today that
 *       {@code fact_trip_update} still holds.
 * </ul>
 */
public class AnalyticsJobParameterCheck implements JobParameterCheck {

    private final BusinessClock clock;
    private final ZoneId agencyZone;
    private final Duration tripUpdateRetention;

    /**
     * @param agencyZone the zone of the local date that "today" is read in
     * @param tripUpdateRetention {@code pti.retention.trip-update}: how far back a date can still be scored
     */
    public AnalyticsJobParameterCheck(BusinessClock clock, ZoneId agencyZone, Duration tripUpdateRetention) {
        this.clock = clock;
        this.agencyZone = agencyZone;
        this.tripUpdateRetention = tripUpdateRetention;
    }

    @Override
    public void check(PtiJob job, String name, String value) {
        if (job == PtiJob.ETA_AGGREGATION && name.equals(EtaAggregationTasklet.HOUR)) {
            checkHour(value);
        } else if (job == PtiJob.ETA_AGGREGATION && name.equals(EtaAggregationTasklet.FORCE)) {
            checkForce(value);
        } else if (job == PtiJob.OTP_SCORECARD && name.equals(OtpScorecardTasklet.SERVICE_DATES)) {
            checkServiceDates(value);
        }
    }

    private void checkHour(String value) {
        Instant hour;
        try {
            hour = Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Parameter hour is not an ISO-8601 instant: " + value, e);
        }
        if (!hour.equals(hour.truncatedTo(ChronoUnit.HOURS))) {
            throw new IllegalArgumentException("Parameter hour must be on the hour: " + value);
        }
        Instant current = clock.instant().truncatedTo(ChronoUnit.HOURS);
        if (hour.isAfter(current)) {
            throw new IllegalArgumentException("Parameter hour " + value + " is after the current hour " + current);
        }
    }

    private static void checkForce(String value) {
        if (!value.equals("true") && !value.equals("false")) {
            throw new IllegalArgumentException("Parameter force must be true or false: " + value);
        }
    }

    private void checkServiceDates(String value) {
        List<LocalDate> dates = OtpServiceDates.parse(value);
        LocalDate today = clock.instant().atZone(agencyZone).toLocalDate();
        LocalDate earliest = today.minusDays(tripUpdateRetention.toDays());
        dates.forEach(date -> OtpServiceDates.requireScorable(date, today, earliest));
    }
}
