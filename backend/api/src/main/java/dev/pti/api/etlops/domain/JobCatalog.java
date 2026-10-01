package dev.pti.api.etlops.domain;

import dev.pti.api.platform.domain.ApiException.FieldError;
import dev.pti.api.platform.domain.ValidationException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The jobs an operator may start by hand and the checks of their parameters (DOC-32 E-33), and which jobs can be
 * restarted at all (DOC-19 §2). The API checks what it can know; {@code etl-batch} checks again (the directories a
 * feed may be loaded from, the retention of trip updates) and may reject the request.
 *
 * <p>The parameters are written in the text form {@code JobRequestPoller} reads: a list is joined with {@code +}, as
 * {@code make job-run} does (DOC-38), a boolean is {@code true} or {@code false}, a time is an ISO-8601 instant.
 */
public final class JobCatalog {

    /** A job and the parameters of the request, in the form the poller takes. */
    public record Submission(String jobName, Map<String, String> parameters) {}

    public static final String GTFS_STATIC_LOAD = "GtfsStaticLoadJob";
    public static final String ETA_AGGREGATION = "EtaAggregationJob";
    public static final String OTP_SCORECARD = "OtpScorecardJob";
    public static final String ANALYTICS_RECOMPUTE = "AnalyticsRecomputeJob";
    public static final String PARTITION_MAINTENANCE = "PartitionMaintenanceJob";

    private static final Set<String> MANUAL_JOBS =
            Set.of(GTFS_STATIC_LOAD, ETA_AGGREGATION, OTP_SCORECARD, ANALYTICS_RECOMPUTE, PARTITION_MAINTENANCE);

    /** The jobs of DOC-19 §2 with "Restart được = Có". The others are short tasklets that simply run again. */
    private static final Set<String> RESTARTABLE = Set.of(
            GTFS_STATIC_LOAD,
            "DlqReplayJob",
            "RawZoneReplayJob",
            PARTITION_MAINTENANCE,
            "OpsRetentionJob",
            "BatchMetadataCleanupJob",
            ETA_AGGREGATION,
            OTP_SCORECARD,
            ANALYTICS_RECOMPUTE);

    private static final List<String> DETECTORS = List.of("BUNCHING", "DISRUPTION", "TICKETING");
    private static final Set<String> SOURCE_URI_SCHEMES = Set.of("https", "s3", "file");
    private static final int MAX_SERVICE_DATES = 31;
    private static final Duration MAX_RECOMPUTE_SPAN = Duration.ofDays(7);

    private JobCatalog() {}

    public static boolean isRestartable(String jobName) {
        return RESTARTABLE.contains(jobName);
    }

    /**
     * Checks a request to run a job.
     *
     * @param businessNow business time now, which the dates and times of the parameters are held against
     * @param feedZone the time zone of the ACTIVE feed, asked only when a parameter needs the local date
     * @throws JobNotAllowedException when the job cannot be started by hand
     * @throws ValidationException when a parameter is missing, unknown or wrong
     */
    public static Submission validate(
            String jobName, Map<String, Object> parameters, Instant businessNow, Supplier<ZoneId> feedZone) {
        if (!MANUAL_JOBS.contains(jobName)) {
            throw new JobNotAllowedException(jobName + " cannot be started manually.");
        }
        Map<String, String> normalised = new LinkedHashMap<>();
        List<FieldError> errors = new ArrayList<>();
        Set<String> allowed = allowedParameters(jobName);
        parameters.keySet().stream()
                .filter(name -> !allowed.contains(name))
                .forEach(name -> errors.add(new FieldError("parameters." + name, "is not a parameter of " + jobName)));
        if (errors.isEmpty()) {
            switch (jobName) {
                case GTFS_STATIC_LOAD -> gtfsStaticLoad(parameters, normalised, errors);
                case ETA_AGGREGATION -> etaAggregation(parameters, businessNow, normalised, errors);
                case OTP_SCORECARD -> otpScorecard(parameters, businessNow, feedZone, normalised, errors);
                case ANALYTICS_RECOMPUTE -> analyticsRecompute(parameters, businessNow, normalised, errors);
                default -> {
                    // PartitionMaintenanceJob has no parameters.
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new ValidationException("The job parameters are not valid.", errors);
        }
        return new Submission(jobName, normalised);
    }

    private static Set<String> allowedParameters(String jobName) {
        return switch (jobName) {
            case GTFS_STATIC_LOAD -> Set.of("sourceUri", "allowReactivate");
            case ETA_AGGREGATION -> Set.of("hour", "force");
            case OTP_SCORECARD -> Set.of("serviceDates");
            case ANALYTICS_RECOMPUTE -> Set.of("detectors", "fromTs", "toTs");
            default -> Set.of();
        };
    }

    private static void gtfsStaticLoad(Map<String, Object> raw, Map<String, String> out, List<FieldError> errors) {
        String sourceUri = text(raw, "sourceUri", errors);
        if (sourceUri != null) {
            String scheme = scheme(sourceUri);
            if (scheme == null || !SOURCE_URI_SCHEMES.contains(scheme)) {
                errors.add(new FieldError("parameters.sourceUri", "must be a URI with the scheme https, s3 or file"));
            } else {
                out.put("sourceUri", sourceUri);
            }
        }
        Boolean allowReactivate = bool(raw, "allowReactivate", errors);
        if (allowReactivate != null) {
            out.put("allowReactivate", allowReactivate.toString());
        }
    }

    private static void etaAggregation(
            Map<String, Object> raw, Instant now, Map<String, String> out, List<FieldError> errors) {
        Instant hour = instant(raw, "hour", errors);
        if (hour != null) {
            if (!hour.equals(hour.truncatedTo(ChronoUnit.HOURS))) {
                errors.add(new FieldError("parameters.hour", "must be on the hour"));
            } else if (hour.isAfter(now)) {
                errors.add(new FieldError("parameters.hour", "must not be in the future"));
            } else {
                out.put("hour", hour.toString());
            }
        }
        Boolean force = bool(raw, "force", errors);
        if (force != null) {
            out.put("force", force.toString());
        }
    }

    private static void otpScorecard(
            Map<String, Object> raw,
            Instant now,
            Supplier<ZoneId> feedZone,
            Map<String, String> out,
            List<FieldError> errors) {
        Object value = raw.get("serviceDates");
        if (!(value instanceof List<?> list) || list.isEmpty() || list.size() > MAX_SERVICE_DATES) {
            errors.add(new FieldError("parameters.serviceDates", "must be a list of 1 to 31 dates"));
            return;
        }
        TreeSet<LocalDate> dates = new TreeSet<>();
        for (Object element : list) {
            try {
                dates.add(LocalDate.parse(String.valueOf(element)));
            } catch (DateTimeParseException e) {
                errors.add(new FieldError("parameters.serviceDates", "must hold dates such as 2026-09-27"));
                return;
            }
        }
        LocalDate today = now.atZone(feedZone.get()).toLocalDate();
        if (dates.stream().anyMatch(date -> !date.isBefore(today))) {
            errors.add(new FieldError("parameters.serviceDates", "every date must be before today (" + today + ")"));
            return;
        }
        out.put("serviceDates", dates.stream().map(LocalDate::toString).collect(Collectors.joining("+")));
    }

    private static void analyticsRecompute(
            Map<String, Object> raw, Instant now, Map<String, String> out, List<FieldError> errors) {
        List<String> detectors = DETECTORS;
        Object value = raw.get("detectors");
        if (value != null) {
            if (!(value instanceof List<?> list) || list.isEmpty()) {
                errors.add(new FieldError("parameters.detectors", "must be a list of " + String.join(", ", DETECTORS)));
                return;
            }
            Set<String> chosen = list.stream().map(String::valueOf).collect(Collectors.toSet());
            if (!DETECTORS.containsAll(chosen)) {
                errors.add(new FieldError("parameters.detectors", "must be a list of " + String.join(", ", DETECTORS)));
                return;
            }
            detectors = DETECTORS.stream().filter(chosen::contains).toList();
        }
        Instant from = required(raw, "fromTs", errors);
        Instant to = required(raw, "toTs", errors);
        if (from == null || to == null) {
            return;
        }
        if (!from.isBefore(to)) {
            errors.add(new FieldError("parameters.fromTs", "must be before toTs"));
        } else if (to.isAfter(now)) {
            errors.add(new FieldError("parameters.toTs", "must not be in the future"));
        } else if (Duration.between(from, to).compareTo(MAX_RECOMPUTE_SPAN) > 0) {
            errors.add(new FieldError("parameters.toTs", "the range must not be longer than 7 days"));
        } else {
            out.put("detectors", String.join("+", detectors));
            out.put("fromTs", from.toString());
            out.put("toTs", to.toString());
        }
    }

    private static @Nullable Instant required(Map<String, Object> raw, String name, List<FieldError> errors) {
        Instant value = instant(raw, name, errors);
        if (value == null && !raw.containsKey(name)) {
            errors.add(new FieldError("parameters." + name, "is required"));
        }
        return value;
    }

    private static @Nullable String text(Map<String, Object> raw, String name, List<FieldError> errors) {
        Object value = raw.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            errors.add(new FieldError("parameters." + name, "must be a text"));
            return null;
        }
        return text;
    }

    private static @Nullable Boolean bool(Map<String, Object> raw, String name, List<FieldError> errors) {
        Object value = raw.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Boolean flag)) {
            errors.add(new FieldError("parameters." + name, "must be true or false"));
            return null;
        }
        return flag;
    }

    private static @Nullable Instant instant(Map<String, Object> raw, String name, List<FieldError> errors) {
        Object value = raw.get(name);
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof String text) {
                return OffsetDateTime.parse(text).toInstant();
            }
        } catch (DateTimeParseException e) {
            // reported below
        }
        errors.add(new FieldError(
                "parameters." + name, "must be an ISO-8601 time with an offset, such as 2026-09-29T21:00:00Z"));
        return null;
    }

    private static @Nullable String scheme(String uri) {
        try {
            return new URI(uri).getScheme();
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
