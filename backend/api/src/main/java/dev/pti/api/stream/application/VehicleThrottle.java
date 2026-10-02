package dev.pti.api.stream.application;

import dev.pti.api.stream.domain.HubEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * At most {@code perSecond} {@code vehicles.batch} per route and second, for every connection at once (DOC-26 §6.3,
 * DOC-33 §5.1). The first event of a quiet route goes out at once (leading edge), so the usual one event per route and
 * second gains no delay; events that come sooner are merged by {@code vehicleId}, keeping the newer
 * {@code eventTimestamp}, and go out when the interval has passed (trailing edge). The merged frame has the id of the
 * newest event in it and the oldest record time, as the publisher does.
 */
public final class VehicleThrottle {

    private final Duration interval;
    private final Map<String, RouteState> routes = new HashMap<>();

    private static final class RouteState {
        @Nullable
        Instant lastSent;

        @Nullable
        HubEvent pending;
    }

    public VehicleThrottle(int perSecond) {
        this.interval = Duration.ofMillis(1000L / Math.max(1, perSecond));
    }

    /** The event to send now, or {@code null} when it was merged into the route's pending one. */
    public synchronized @Nullable HubEvent offer(HubEvent event, Instant now) {
        String route = event.routeId();
        if (route == null) {
            return event;
        }
        RouteState state = routes.computeIfAbsent(route, r -> new RouteState());
        if (state.pending == null && (state.lastSent == null || !now.isBefore(state.lastSent.plus(interval)))) {
            state.lastSent = now;
            return event;
        }
        state.pending = state.pending == null ? event : merge(state.pending, event);
        return null;
    }

    /** The pending events whose interval has passed; called a few times a second. */
    public synchronized List<HubEvent> due(Instant now) {
        List<HubEvent> due = new ArrayList<>();
        for (RouteState state : routes.values()) {
            if (state.pending != null && (state.lastSent == null || !now.isBefore(state.lastSent.plus(interval)))) {
                due.add(state.pending);
                state.pending = null;
                state.lastSent = now;
            }
        }
        return due;
    }

    /** {@code later}'s vehicles over {@code earlier}'s, by {@code vehicleId}, the newer position winning. */
    static HubEvent merge(HubEvent earlier, HubEvent later) {
        Map<String, Map<String, Object>> byVehicle = new LinkedHashMap<>();
        List<Object> anonymous = new ArrayList<>();
        for (HubEvent event : List.of(earlier, later)) {
            for (Object vehicle : vehicles(event)) {
                if (!(vehicle instanceof Map<?, ?> map) || !(map.get("vehicleId") instanceof String id)) {
                    anonymous.add(vehicle);
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> position = (Map<String, Object>) map;
                byVehicle.merge(id, position, (kept, next) -> newer(kept, next));
            }
        }
        List<Object> merged = new ArrayList<>(byVehicle.values());
        merged.addAll(anonymous);
        Map<String, Object> data = new LinkedHashMap<>(later.data());
        data.put("vehicles", merged);
        Instant source = earliest(earlier.sourceRecordTs(), later.sourceRecordTs());
        return later.withData(later.id(), data, source);
    }

    private static List<?> vehicles(HubEvent event) {
        return event.data().get("vehicles") instanceof List<?> list ? list : List.of();
    }

    private static Map<String, Object> newer(Map<String, Object> kept, Map<String, Object> next) {
        Instant keptAt = timestamp(kept);
        Instant nextAt = timestamp(next);
        if (keptAt != null && nextAt != null && nextAt.isBefore(keptAt)) {
            return kept;
        }
        return next;
    }

    private static @Nullable Instant timestamp(Map<String, Object> position) {
        if (position.get("eventTimestamp") instanceof String text) {
            try {
                return Instant.parse(text);
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        return null;
    }

    private static @Nullable Instant earliest(@Nullable Instant a, @Nullable Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isBefore(b) ? a : b;
    }
}
