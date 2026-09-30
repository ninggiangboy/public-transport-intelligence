package dev.pti.api.platform.domain;

import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A use case result together with the event time of the newest data it reflects (DOC-31 §7.3). The controller sets
 * {@code X-Data-As-Of} from {@code asOf} and leaves the header out when it is {@code null}: a source that never had
 * data has no as-of.
 */
public record WithAsOf<T>(T value, @Nullable Instant asOf) {

    public static <T> WithAsOf<T> of(T value, Optional<Instant> asOf) {
        return new WithAsOf<>(value, asOf.orElse(null));
    }
}
