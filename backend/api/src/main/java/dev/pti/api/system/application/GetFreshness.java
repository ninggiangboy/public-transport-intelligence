package dev.pti.api.system.application;

import dev.pti.api.platform.domain.ServiceUnavailableException;
import dev.pti.api.system.application.port.FreshnessSnapshots;
import dev.pti.api.system.domain.Freshness;
import dev.pti.api.system.domain.Freshness.SourceStatus;
import dev.pti.api.system.domain.FreshnessSnapshot;
import dev.pti.api.system.domain.FreshnessThresholds;
import dev.pti.api.system.domain.SourceKind;
import dev.pti.common.time.BusinessClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code GET /system/freshness} (DOC-32 E-60): reads the result of the probe, never the database, and works out the
 * age and staleness of each source at this moment. A result older than {@code max-probe-age} (60 s), or none yet, is
 * a 503: the probe has stopped, so no figure can be trusted.
 */
public final class GetFreshness {

    /** {@code Retry-After} of the 503 (DOC-30 §3.2). */
    private static final int RETRY_AFTER_SECONDS = 5;

    private final FreshnessSnapshots snapshots;
    private final BusinessClock clock;
    private final FreshnessThresholds thresholds;
    private final Duration maxProbeAge;

    public GetFreshness(
            FreshnessSnapshots snapshots, BusinessClock clock, FreshnessThresholds thresholds, Duration maxProbeAge) {
        this.snapshots = snapshots;
        this.clock = clock;
        this.thresholds = thresholds;
        this.maxProbeAge = maxProbeAge;
    }

    /** @throws ServiceUnavailableException when no probe result is young enough */
    public Freshness execute() {
        Instant now = clock.instant();
        FreshnessSnapshot snapshot = snapshots
                .current()
                .filter(current -> !now.isAfter(current.checkedAt().plus(maxProbeAge)))
                .orElseThrow(() -> new ServiceUnavailableException(
                        "Freshness data is not available right now.", RETRY_AFTER_SECONDS));
        List<SourceStatus> sources = new ArrayList<>();
        boolean stale = false;
        for (SourceKind source : SourceKind.values()) {
            SourceStatus status = status(source, snapshot, now);
            sources.add(status);
            stale |= status.stale() && source.gtfsRealtime();
        }
        return new Freshness(
                now,
                clock.offset(),
                snapshot.checkedAt(),
                stale,
                snapshot.activeFeed(),
                sources,
                snapshot.etaComputedAt(),
                snapshot.otpComputedAt(),
                snapshot.probeError());
    }

    private SourceStatus status(SourceKind source, FreshnessSnapshot snapshot, Instant now) {
        long staleAfter = thresholds.staleAfter(source).toSeconds();
        Instant lastEventAt = snapshot.lastEventAt().get(source);
        if (lastEventAt == null) {
            return new SourceStatus(source, null, null, staleAfter, true);
        }
        long age = Math.max(0, Duration.between(lastEventAt, now).toSeconds());
        return new SourceStatus(source, lastEventAt, age, staleAfter, age > staleAfter);
    }
}
