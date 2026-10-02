package dev.pti.etl.realtime.application;

import dev.pti.common.events.Audience;
import dev.pti.common.events.UiChannel;
import dev.pti.common.events.UiEvent;
import dev.pti.common.time.BusinessClock;
import dev.pti.etl.realtime.application.port.RealtimeEventSink;
import dev.pti.etl.realtime.domain.VehiclePosition;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * {@code vehicles.batch} (DOC-20 §8, DOC-33 §5.1): the positions of the committed polls are kept per route, the
 * latest per vehicle, and once a second every route that changed gets one event with them. The record time of the
 * oldest data in it is {@code source_record_ts}, from which the API measures the end-to-end latency (DR-57). Each pod
 * publishes what it consumed itself, so there is no lock.
 */
public final class VehiclesBatchPublisher {

    static final String TYPE = "vehicles.batch";

    private final RealtimeEventSink sink;
    private final BusinessClock clock;
    private final Map<String, Pending> pending = new HashMap<>();

    private static final class Pending {
        final Map<String, VehiclePosition> vehicles = new LinkedHashMap<>();

        @Nullable
        Instant oldestRecord;

        @Nullable
        Instant oldestCommit;
    }

    public VehiclesBatchPublisher(RealtimeEventSink sink, BusinessClock clock) {
        this.sink = sink;
        this.clock = clock;
    }

    /** The positions of one committed poll; {@code recordTs} is its oldest Kafka record time. */
    public synchronized void add(List<VehiclePosition> positions, @Nullable Instant recordTs, Instant committedAt) {
        for (VehiclePosition position : positions) {
            Pending route = pending.computeIfAbsent(position.routeId(), r -> new Pending());
            route.vehicles.merge(
                    position.vehicleId(),
                    position,
                    (kept, next) -> next.eventTimestamp().isBefore(kept.eventTimestamp()) ? kept : next);
            route.oldestRecord = earliest(route.oldestRecord, recordTs);
            route.oldestCommit = earliest(route.oldestCommit, committedAt);
        }
    }

    /** One event per route that changed since the last flush. */
    public void flush() {
        Map<String, Pending> due;
        synchronized (this) {
            if (pending.isEmpty()) {
                return;
            }
            due = new LinkedHashMap<>(pending);
            pending.clear();
        }
        Instant now = clock.realNow();
        due.forEach((routeId, route) -> {
            List<Object> vehicles = new ArrayList<>();
            route.vehicles.values().forEach(position -> vehicles.add(position.toData()));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("routeId", routeId);
            data.put("vehicles", vehicles);
            UiEvent event = UiEvent.of(
                    now, TYPE, UiChannel.VEHICLES, Audience.PUBLIC, routeId, routeId, route.oldestRecord, data);
            sink.publish(event, route.oldestCommit == null ? now : route.oldestCommit);
        });
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
