package dev.pti.api.alert.application;

import dev.pti.api.alert.domain.AlertState;
import dev.pti.api.alert.domain.AlertType;
import dev.pti.common.events.Audience;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The filters of the alert list (DOC-32 E-20). {@code audiences} is what the caller asked for (every audience when
 * empty); the use case narrows it to what the caller may see. {@code from} and {@code to} bound {@code created_at};
 * with {@code since} the lower bound is exclusive and {@code from} is the same instant.
 */
public record AlertQuery(
        Set<Audience> audiences,
        List<AlertType> types,
        List<Integer> severities,
        List<String> routeIds,
        AlertState state,
        Instant from,
        Instant to,
        @Nullable Instant since) {

    public AlertQuery {
        audiences = Set.copyOf(audiences);
        types = List.copyOf(types);
        severities = List.copyOf(severities);
        routeIds = List.copyOf(routeIds);
    }

    AlertQuery withAudiences(Set<Audience> narrowed) {
        return new AlertQuery(narrowed, types, severities, routeIds, state, from, to, since);
    }
}
