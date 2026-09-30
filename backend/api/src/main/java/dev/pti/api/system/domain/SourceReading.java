package dev.pti.api.system.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** What one round trip of the freshness probe found; {@code null} means the source has no data. */
public record SourceReading(
        @Nullable Instant vehiclePosition,
        @Nullable Instant tripUpdate,
        @Nullable Instant ticketSales,
        @Nullable Instant otpComputedAt) {}
