package dev.pti.api.system.adapter.out.cache;

import dev.pti.api.platform.application.port.DataAsOfReader;
import dev.pti.api.platform.domain.AsOfKind;
import dev.pti.api.system.application.port.FreshnessSnapshots;
import dev.pti.api.system.domain.FreshnessSnapshot;
import dev.pti.api.system.domain.SourceKind;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * {@code X-Data-As-Of} for every controller (DOC-31 §7.3), answered from the last probe result: no query per request,
 * at most 15 seconds older than the truth, and the same clock as the stale banner.
 */
@Component
public final class ProbeDataAsOfReader implements DataAsOfReader {

    private final FreshnessSnapshots snapshots;

    public ProbeDataAsOfReader(FreshnessSnapshots snapshots) {
        this.snapshots = snapshots;
    }

    @Override
    public Optional<Instant> asOf(AsOfKind kind) {
        return snapshots.current().flatMap(snapshot -> of(kind, snapshot));
    }

    private static Optional<Instant> of(AsOfKind kind, FreshnessSnapshot snapshot) {
        return switch (kind) {
            case VEHICLE_POSITION -> source(snapshot, SourceKind.GTFS_RT_VEHICLE_POSITION);
            case TRIP_UPDATE -> source(snapshot, SourceKind.GTFS_RT_TRIP_UPDATE);
            case TICKET_SALES -> source(snapshot, SourceKind.TICKETING_SALES);
            case ETA_PREDICTION -> Optional.ofNullable(snapshot.etaComputedAt());
            case OTP_SCORECARD -> Optional.ofNullable(snapshot.otpComputedAt());
        };
    }

    private static Optional<Instant> source(FreshnessSnapshot snapshot, SourceKind source) {
        return Optional.ofNullable(snapshot.lastEventAt().get(source));
    }
}
