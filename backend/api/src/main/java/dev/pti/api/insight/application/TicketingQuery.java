package dev.pti.api.insight.application;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The filters of the ticketing anomaly list (DOC-32 E-15): {@code detected_at} in {@code [from, to)}, one sale point,
 * some categories ({@code unclassified} asks for the anomalies that have none yet), some severities and a trigger.
 *
 * @param categories the category values, not including {@code unclassified}
 */
public record TicketingQuery(
        Instant from,
        Instant to,
        @Nullable String salePointId,
        List<String> categories,
        boolean unclassified,
        List<Integer> severities,
        @Nullable String trigger) {

    public TicketingQuery {
        categories = List.copyOf(categories);
        severities = List.copyOf(severities);
    }

    /** True when the caller restricted the category in any way, so that not every anomaly qualifies. */
    public boolean filtersCategory() {
        return unclassified || !categories.isEmpty();
    }
}
