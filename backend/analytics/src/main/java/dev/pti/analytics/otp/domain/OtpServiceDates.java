package dev.pti.analytics.otp.domain;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * Which service dates an OTP run scores, and which a request may ask for (DOC-23 §8.2). A service date is the day a
 * trip started; it is scored only once that day is over, and only while {@code fact_trip_update} still holds it.
 */
public final class OtpServiceDates {

    private OtpServiceDates() {}

    /**
     * The dates of the nightly run: yesterday and {@code recomputeDays} days before it, newest first. The older days are
     * scored again because a trip of yesterday can run past the job's 03:00 (GTFS times go to 27:00) and because a
     * replay can add data.
     */
    public static List<LocalDate> defaults(LocalDate runDate, int recomputeDays) {
        List<LocalDate> dates = new ArrayList<>();
        for (int back = 1; back <= 1 + recomputeDays; back++) {
            dates.add(runDate.minusDays(back));
        }
        return dates;
    }

    /**
     * Newest first, each date once.
     *
     * @throws IllegalArgumentException when no date is given
     */
    public static List<LocalDate> normalize(List<LocalDate> dates) {
        if (dates.isEmpty()) {
            throw new IllegalArgumentException("At least one service date is required");
        }
        TreeSet<LocalDate> sorted = new TreeSet<>(Comparator.reverseOrder());
        sorted.addAll(dates);
        return List.copyOf(sorted);
    }

    /**
     * @param today the local date of the business time now
     * @param earliest the oldest date that {@code fact_trip_update} still holds; {@code null} when that is not known
     * @throws IllegalArgumentException for a date that is not before {@code today}, or older than {@code earliest}
     */
    public static void requireScorable(LocalDate date, LocalDate today, @Nullable LocalDate earliest) {
        if (!date.isBefore(today)) {
            throw new IllegalArgumentException("Service date " + date + " is not before today (" + today + ")");
        }
        if (earliest != null && date.isBefore(earliest)) {
            throw new IllegalArgumentException(
                    "Service date " + date + " is older than the retention of trip updates (from " + earliest + ")");
        }
    }

    /**
     * Reads a list of ISO dates separated by {@code +} (as {@code make job-run} writes a list) or by commas, with the
     * brackets and quotes of a JSON array tolerated.
     *
     * @throws IllegalArgumentException when the text holds no date or a date that is not ISO-8601
     */
    public static List<LocalDate> parse(String text) {
        List<LocalDate> dates = new ArrayList<>();
        for (String item : text.replaceAll("[\\[\\]\"\\s]", "").split("[+,]")) {
            if (item.isEmpty()) {
                continue;
            }
            try {
                dates.add(LocalDate.parse(item));
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Service date is not an ISO-8601 date: " + item, e);
            }
        }
        if (dates.isEmpty()) {
            throw new IllegalArgumentException("At least one service date is required");
        }
        return dates;
    }
}
