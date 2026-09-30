package dev.pti.api.system.domain;

import java.time.Duration;

/** After how long a source counts as stale (DOC-32 E-60): 120 s for GTFS-realtime (DR-38), 900 s for ticketing. */
public record FreshnessThresholds(Duration gtfsRealtime, Duration ticketing) {

    public Duration staleAfter(SourceKind source) {
        return source.gtfsRealtime() ? gtfsRealtime : ticketing;
    }
}
