package dev.pti.api.etlops.domain;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The filters of {@code GET /etl/dlq} (DOC-32 E-40); an empty list means "all". {@code categories} and {@code
 * severities} accept {@code unclassified} for a dead letter the model has not classified. {@code from} and {@code to}
 * are optional and have no default.
 */
public record DeadLetterFilter(
        List<String> statuses,
        List<String> sources,
        List<String> stages,
        List<String> categories,
        List<String> severities,
        List<String> ruleIds,
        @Nullable Instant from,
        @Nullable Instant to) {

    public static final String UNCLASSIFIED = "unclassified";

    public DeadLetterFilter {
        statuses = List.copyOf(statuses);
        sources = List.copyOf(sources);
        stages = List.copyOf(stages);
        categories = List.copyOf(categories);
        severities = List.copyOf(severities);
        ruleIds = List.copyOf(ruleIds);
    }
}
