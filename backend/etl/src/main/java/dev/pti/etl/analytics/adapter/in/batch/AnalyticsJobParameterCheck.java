package dev.pti.etl.analytics.adapter.in.batch;

import dev.pti.analytics.core.domain.Detector;
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
import java.util.Map;

/**
 * Refuses a {@code job_request} for an analytics job whose parameters cannot run (DOC-23 §15): the request ends
 * {@code REJECTED} with the message, before any execution exists. The messages are English, as they are shown to the
 * operator as written.
 *
 * <ul>
 *   <li>{@code EtaAggregationJob}: {@code hour} is an ISO-8601 instant on the hour that is not after the current
 *       hour, {@code force} is {@code true} or {@code false};
 *   <li>{@code OtpScorecardJob}: every date of {@code serviceDates} is an ISO-8601 date before today that
 *       {@code fact_trip_update} still holds;
 *   <li>{@code AnalyticsRecomputeJob} (DOC-23 §11.5): {@code detectors} names detectors joined by {@code +};
 *       {@code fromTs} and {@code toTs} are ISO-8601 instants, both present, {@code fromTs < toTs ≤ now} and at most
 *       7 days apart.
 * </ul>
 */
public class AnalyticsJobParameterCheck implements JobParameterCheck {

    /** The longest range {@code AnalyticsRecomputeJob} takes (DOC-23 §11.5, AN-R-11). */
    static final Duration MAX_RECOMPUTE_RANGE = Duration.ofDays(7);

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
        } else if (job == PtiJob.ANALYTICS_RECOMPUTE && name.equals(RecomputePlans.DETECTORS)) {
            checkDetectors(value);
        } else if (job == PtiJob.ANALYTICS_RECOMPUTE
                && (name.equals(RecomputePlans.FROM_TS) || name.equals(RecomputePlans.TO_TS))) {
            instant(name, value);
        }
    }

    @Override
    public void checkAll(PtiJob job, Map<String, String> parameters) {
        if (job != PtiJob.ANALYTICS_RECOMPUTE) {
            return;
        }
        String fromValue = parameters.get(RecomputePlans.FROM_TS);
        String toValue = parameters.get(RecomputePlans.TO_TS);
        if (fromValue == null || toValue == null) {
            throw new IllegalArgumentException("Parameters fromTs and toTs are required for AnalyticsRecomputeJob");
        }
        Instant from = instant(RecomputePlans.FROM_TS, fromValue);
        Instant to = instant(RecomputePlans.TO_TS, toValue);
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("Parameter fromTs " + from + " must be before toTs " + to);
        }
        Instant now = clock.instant();
        if (to.isAfter(now)) {
            throw new IllegalArgumentException("Parameter toTs " + to + " is after the current time " + now);
        }
        if (Duration.between(from, to).compareTo(MAX_RECOMPUTE_RANGE) > 0) {
            throw new IllegalArgumentException("The range from " + from + " to " + to + " is longer than "
                    + MAX_RECOMPUTE_RANGE.toDays() + " days");
        }
    }

    private static void checkDetectors(String value) {
        try {
            if (value.isBlank()) {
                throw new IllegalArgumentException(value);
            }
            RecomputePlans.detectors(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Parameter detectors must be detectors joined by +, out of " + List.of(Detector.values()) + ": "
                            + value,
                    e);
        }
    }

    private static Instant instant(String name, String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Parameter " + name + " is not an ISO-8601 instant: " + value, e);
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
