package dev.pti.etl.reference;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Scheduled arrivals per trip for the service dates in use (DOC-21 §6.1). Each date holds only the trips running
 * that day, as {@code int[2][]}: {@code [0]} the stop sequences in increasing order, {@code [1]} the arrival
 * seconds. Dates are loaded on first use and kept for {@code today}, {@code yesterday} and a few replay days.
 */
public final class StopTimes {

    private final LoadingCache<LocalDate, Map<String, int[][]>> byDate;

    public StopTimes(Function<LocalDate, Map<String, int[][]>> loader, int maxDates) {
        this.byDate = Caffeine.newBuilder().maximumSize(maxDates).build(loader::apply);
    }

    /** Arrival seconds of {@code (trip, stop_sequence)} on a service date, or {@code null} when unknown. */
    public @Nullable Integer arrivalSeconds(String tripId, int stopSequence, LocalDate serviceDate) {
        int[][] times = byDate.get(serviceDate).get(tripId);
        if (times == null) {
            return null;
        }
        int i = Arrays.binarySearch(times[0], stopSequence);
        return i < 0 ? null : times[1][i];
    }

    /** Loads a date ahead of use, e.g. today and yesterday after a refresh. */
    public void warm(LocalDate serviceDate) {
        byDate.get(serviceDate);
    }
}
