package dev.pti.api.insight.application;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The filters of the disruption list (DOC-32 E-12), as for bunching; {@code publicOnly} is the anonymous view. */
public record DisruptionQuery(
        Instant from,
        Instant to,
        List<String> routeIds,
        @Nullable String status,
        boolean publicOnly) {

    public DisruptionQuery {
        routeIds = List.copyOf(routeIds);
    }
}
