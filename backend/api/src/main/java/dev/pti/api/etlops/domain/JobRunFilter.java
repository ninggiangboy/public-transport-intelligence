package dev.pti.api.etlops.domain;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The filters of {@code GET /etl/jobs} (DOC-32 E-30); an empty list means "all". */
public record JobRunFilter(
        Instant from, Instant to, @Nullable RunKind kind, List<String> names, List<String> statuses) {

    public JobRunFilter {
        names = List.copyOf(names);
        statuses = List.copyOf(statuses);
    }
}
