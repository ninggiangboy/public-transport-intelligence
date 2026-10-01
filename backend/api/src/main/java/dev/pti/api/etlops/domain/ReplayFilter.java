package dev.pti.api.etlops.domain;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The filters of {@code GET /etl/replays} (DOC-32 E-51). */
public record ReplayFilter(
        Instant from,
        Instant to,
        @Nullable ReplayKind kind,
        List<String> statuses,
        @Nullable String source,
        @Nullable String requestedBy) {

    public ReplayFilter {
        statuses = List.copyOf(statuses);
    }
}
