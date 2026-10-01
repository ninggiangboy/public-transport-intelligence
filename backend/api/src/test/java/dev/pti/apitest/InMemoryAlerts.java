package dev.pti.apitest;

import dev.pti.api.alert.application.AlertQuery;
import dev.pti.api.alert.application.port.AlertReader;
import dev.pti.api.alert.application.port.AlertStore;
import dev.pti.api.alert.domain.Alert;
import dev.pti.api.alert.domain.NewAlert;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The alert ports in memory, with the filters, the keyset order and the write rules of the SQL: an acknowledgement
 * sticks to the first operator, an insert with a known dedup key changes nothing, a resolution applies once. A test
 * that wants data autowires it, calls {@link #reset()} first, and holds the lock {@code in-memory-alerts}.
 */
public final class InMemoryAlerts {

    /** The database clock of the writes. */
    public static final Instant NOW = Instant.parse("2026-09-29T21:14:02.123456Z");

    public final Map<UUID, Alert> alerts = new ConcurrentHashMap<>();
    private final Map<String, UUID> byDedupKey = new ConcurrentHashMap<>();

    /** Every statement that reached the store, to check that a repeated request writes nothing. */
    public final List<String> statements = new CopyOnWriteArrayList<>();

    public void reset() {
        alerts.clear();
        byDedupKey.clear();
        statements.clear();
    }

    /** Stores an alert as if it had been inserted with this dedup key. */
    public void add(Alert alert, String dedupKey) {
        alerts.put(alert.id(), alert);
        byDedupKey.put(dedupKey, alert.id());
    }

    public void add(Alert alert) {
        add(alert, "test:" + alert.id());
    }

    public final AlertReader reader = new AlertReader() {
        @Override
        public Page<Alert> list(AlertQuery query, PageRequest request) {
            List<Alert> rows = alerts.values().stream()
                    .filter(a -> !a.createdAt().isBefore(query.from())
                            && a.createdAt().isBefore(query.to()))
                    .filter(a -> query.since() == null || a.createdAt().isAfter(query.since()))
                    .filter(a -> query.audiences().contains(a.audience()))
                    .filter(a -> query.types().isEmpty() || query.types().contains(a.type()))
                    .filter(a ->
                            query.severities().isEmpty() || query.severities().contains(a.severity()))
                    .filter(a -> query.routeIds().isEmpty()
                            || (a.routeId() != null && query.routeIds().contains(a.routeId())))
                    .filter(a -> switch (query.state()) {
                        case ALL -> true;
                        case OPEN -> a.resolvedAt() == null;
                        case UNACKNOWLEDGED -> a.resolvedAt() == null && a.acknowledgedAt() == null;
                    })
                    .toList();
            return InMemoryPaging.page(rows, request, Alert::createdAt, Alert::id);
        }
    };

    public final AlertStore store = new AlertStore() {
        @Override
        public Optional<Alert> find(UUID id) {
            statements.add("find");
            return Optional.ofNullable(alerts.get(id));
        }

        @Override
        public Optional<Alert> acknowledge(UUID id, String actor) {
            statements.add("acknowledge");
            Alert current = alerts.get(id);
            if (current == null || current.acknowledgedAt() != null) {
                return Optional.empty();
            }
            Alert updated = new Alert(
                    current.id(),
                    current.type(),
                    current.severity(),
                    current.audience(),
                    current.routeId(),
                    current.refTable(),
                    current.refId(),
                    current.title(),
                    current.body(),
                    current.createdAt(),
                    actor,
                    NOW,
                    current.resolvedAt());
            alerts.put(id, updated);
            return Optional.of(updated);
        }

        @Override
        public Optional<Alert> insertIfAbsent(NewAlert alert) {
            statements.add("insert");
            if (byDedupKey.containsKey(alert.dedupKey())) {
                return Optional.empty();
            }
            Alert row = new Alert(
                    alert.id(),
                    alert.type(),
                    alert.severity(),
                    alert.audience(),
                    alert.routeId(),
                    null,
                    null,
                    alert.title(),
                    alert.body(),
                    NOW,
                    null,
                    null,
                    null);
            add(row, alert.dedupKey());
            return Optional.of(row);
        }

        @Override
        public Optional<Alert> resolve(String dedupKey) {
            statements.add("resolve");
            UUID id = byDedupKey.get(dedupKey);
            Alert current = id == null ? null : alerts.get(id);
            if (current == null || current.resolvedAt() != null) {
                return Optional.empty();
            }
            Alert updated = new Alert(
                    current.id(),
                    current.type(),
                    current.severity(),
                    current.audience(),
                    current.routeId(),
                    current.refTable(),
                    current.refId(),
                    current.title(),
                    current.body(),
                    current.createdAt(),
                    current.acknowledgedBy(),
                    current.acknowledgedAt(),
                    NOW);
            alerts.put(current.id(), updated);
            return Optional.of(updated);
        }
    };
}
