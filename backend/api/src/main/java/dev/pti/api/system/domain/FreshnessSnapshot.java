package dev.pti.api.system.domain;

import dev.pti.api.platform.domain.ActiveFeed;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The result of the last freshness probe (DOC-32 E-60), on the business time axis. Endpoints read it; none of them
 * queries the database for it. {@code lastEventAt} has no entry for a source that never had data. {@code etaReadAt}
 * remembers when the expensive ETA query last ran, because the probe repeats it only every {@code insight-interval}.
 * {@code probeError} is set when the latest probe failed and this is an older result.
 */
public record FreshnessSnapshot(
        Instant checkedAt,
        @Nullable ActiveFeed activeFeed,
        Map<SourceKind, Instant> lastEventAt,
        @Nullable Instant etaComputedAt,
        @Nullable Instant etaReadAt,
        @Nullable Instant otpComputedAt,
        boolean probeError) {

    public FreshnessSnapshot {
        lastEventAt = Map.copyOf(lastEventAt);
    }

    public FreshnessSnapshot withProbeError() {
        return new FreshnessSnapshot(checkedAt, activeFeed, lastEventAt, etaComputedAt, etaReadAt, otpComputedAt, true);
    }
}
