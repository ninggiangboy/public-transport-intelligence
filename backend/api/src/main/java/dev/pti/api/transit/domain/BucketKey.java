package dev.pti.api.transit.domain;

import java.time.Instant;
import java.time.LocalDate;

/** The identity of a delay bucket: what differs between the three {@link BucketSize}s. */
public sealed interface BucketKey {

    /** The hour that starts at {@code start}. */
    record Hourly(Instant start) implements BucketKey {}

    /** The local date of the scheduled arrival, in the timezone of the feed. */
    record Daily(LocalDate date) implements BucketKey {}

    /** ISO day of the week, 1 = Monday to 7 = Sunday, and local hour 0-23. */
    record WeekHour(int dayOfWeek, int hourOfDay) implements BucketKey {}
}
