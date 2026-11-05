package dev.pti.etl.reference.adapter.out.jdbc;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import dev.pti.etl.reference.domain.StopTimes;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * {@link StopTimes} backed by a Caffeine cache bounded to the service dates in use (today, yesterday and the replay
 * days, DOC-21 §6.1): the loader hits the warehouse, the cache keeps the latest dates. Each date is a
 * {@code Map<tripId, int[2][]>}: {@code [0]} the stop sequences in increasing order, {@code [1]} the arrival seconds.
 */
public final class CaffeineStopTimes implements StopTimes {

    private final LoadingCache<LocalDate, Map<String, int[][]>> byDate;

    public CaffeineStopTimes(Function<LocalDate, Map<String, int[][]>> loader, int maxDates) {
        this.byDate = Caffeine.newBuilder().maximumSize(maxDates).build(loader::apply);
    }

    @Override
    public @Nullable Integer arrivalSeconds(String tripId, int stopSequence, LocalDate serviceDate) {
        int[][] times = byDate.get(serviceDate).get(tripId);
        if (times == null) {
            return null;
        }
        int i = Arrays.binarySearch(times[0], stopSequence);
        return i < 0 ? null : times[1][i];
    }

    @Override
    public void warm(LocalDate serviceDate) {
        byDate.get(serviceDate);
    }
}
