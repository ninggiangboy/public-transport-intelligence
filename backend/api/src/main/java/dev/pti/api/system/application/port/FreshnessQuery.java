package dev.pti.api.system.application.port;

import dev.pti.api.system.domain.SourceReading;
import java.time.Instant;
import java.util.Optional;

/** The database side of the freshness probe (DOC-32 E-60), read through the {@code reader} datasource. */
public interface FreshnessQuery {

    /** The cheap probe: newest event per source and the newest OTP computation, one round trip. */
    SourceReading readSources();

    /** {@code max(computed_at)} of the ETA table; it scans the table, so the probe asks for it only now and then. */
    Optional<Instant> readEtaComputedAt();
}
