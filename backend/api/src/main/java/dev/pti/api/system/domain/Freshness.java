package dev.pti.api.system.domain;

import dev.pti.api.platform.domain.ActiveFeed;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The answer of {@code GET /system/freshness} (DOC-32 E-60): how old each source is at this moment, whether the
 * system is stale, and what the ACTIVE feed is. {@code stale} is the stale banner: one of the GTFS-realtime sources is
 * stale (ticketing alone does not raise it).
 */
public record Freshness(
        Instant businessNow,
        Duration clockOffset,
        Instant checkedAt,
        boolean stale,
        @Nullable ActiveFeed activeFeed,
        List<SourceStatus> sources,
        @Nullable Instant etaComputedAt,
        @Nullable Instant otpComputedAt,
        boolean probeError) {

    public Freshness {
        sources = List.copyOf(sources);
    }

    /** One source: {@code lastEventAt} and {@code ageSeconds} are absent when it never had data, and it is stale. */
    public record SourceStatus(
            SourceKind source,
            @Nullable Instant lastEventAt,
            @Nullable Long ageSeconds,
            long staleAfterSeconds,
            boolean stale) {}
}
