package dev.pti.analytics.retention.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * The point in time before which a row has expired. A table is compared with one of the two values: its timestamp
 * column with {@code instant}, the scorecard's {@code service_date} with {@code serviceDate}.
 */
public record RetentionCutoff(Instant instant, LocalDate serviceDate) {}
