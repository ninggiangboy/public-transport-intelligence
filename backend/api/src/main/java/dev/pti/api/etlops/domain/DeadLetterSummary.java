package dev.pti.api.etlops.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** The counters of {@code GET /etl/dlq/summary} (DOC-32 E-41): the open dead letters by status, source and severity. */
public record DeadLetterSummary(
        long open,
        Map<String, Long> byStatus,
        Map<String, Long> openBySource,
        Map<String, Long> openBySeverity,
        long createdLastHour) {

    /** One group of the query: how many open dead letters have this status, source and severity. */
    public record Group(
            DeadLetterStatus status,
            String source,
            @Nullable Integer severity,
            long count) {}

    /** Every open status and every severity is present, with 0 when there is none, so a screen needs no defaults. */
    public static DeadLetterSummary of(List<Group> groups, long createdLastHour) {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        DeadLetterStatus.OPEN.forEach(status -> byStatus.put(status.name(), 0L));
        Map<String, Long> bySource = new LinkedHashMap<>();
        Map<String, Long> bySeverity = new LinkedHashMap<>();
        for (String severity : List.of("0", "1", "2", DeadLetterFilter.UNCLASSIFIED)) {
            bySeverity.put(severity, 0L);
        }
        long open = 0;
        for (Group group : groups) {
            open += group.count();
            byStatus.merge(group.status().name(), group.count(), Long::sum);
            bySource.merge(group.source(), group.count(), Long::sum);
            String severity = Objects.toString(group.severity(), DeadLetterFilter.UNCLASSIFIED);
            bySeverity.merge(severity, group.count(), Long::sum);
        }
        return new DeadLetterSummary(open, byStatus, bySource, bySeverity, createdLastHour);
    }
}
