package dev.pti.etl.reference.domain;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * Scheduled arrivals per trip for the service dates in use (DOC-21 §6.1). Each date holds only the trips running
 * that day. Dates are loaded on first use; the cache (Caffeine, in {@code reference.adapter.out.jdbc}) is an
 * implementation detail, so this is a plain interface of the domain.
 */
public interface StopTimes {

    /** Arrival seconds of {@code (trip, stop_sequence)} on a service date, or {@code null} when unknown. */
    @Nullable
    Integer arrivalSeconds(String tripId, int stopSequence, LocalDate serviceDate);

    /** Loads a date ahead of use, e.g. today and yesterday after a refresh. */
    void warm(LocalDate serviceDate);
}
