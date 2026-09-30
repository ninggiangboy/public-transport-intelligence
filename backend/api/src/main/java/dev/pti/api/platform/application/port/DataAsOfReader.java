package dev.pti.api.platform.application.port;

import dev.pti.api.platform.domain.AsOfKind;
import java.time.Instant;
import java.util.Optional;

/**
 * The event time of the newest data of one kind, for {@code X-Data-As-Of} (DOC-31 §7.3). It reads the result of the
 * freshness probe, so it is at most 15 seconds older than the truth and costs no query; {@code empty} when the source
 * never had data or no probe has succeeded yet.
 */
public interface DataAsOfReader {

    Optional<Instant> asOf(AsOfKind kind);
}
