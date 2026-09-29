package dev.pti.etl.write;

import dev.pti.etl.core.WriteSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * What a chunk write did, counted per message.
 *
 * @param written messages that changed at least one row
 * @param duplicateInChunk messages superseded within the chunk (DQ-02)
 * @param duplicateRegistry messages whose hash the dedup registry had already seen
 * @param duplicateGuard messages every row of which the upsert guard blocked
 * @param rejected messages a chunk rule sent to the dead-letter queue
 */
public record WriteOutcome(
        List<WriteSet> written, int duplicateInChunk, int duplicateRegistry, int duplicateGuard, int rejected) {

    public WriteOutcome {
        written = List.copyOf(written);
    }

    public static WriteOutcome none() {
        return new WriteOutcome(List.of(), 0, 0, 0, 0);
    }

    public int writtenCount() {
        return written.size();
    }

    public int duplicate() {
        return duplicateInChunk + duplicateRegistry + duplicateGuard;
    }

    public WriteOutcome plus(WriteOutcome other) {
        List<WriteSet> all = new ArrayList<>(written);
        all.addAll(other.written);
        return new WriteOutcome(
                all,
                duplicateInChunk + other.duplicateInChunk,
                duplicateRegistry + other.duplicateRegistry,
                duplicateGuard + other.duplicateGuard,
                rejected + other.rejected);
    }

    public Optional<Instant> minEventTimestamp() {
        return written.stream().map(WriteSet::eventTimestamp).min(Instant::compareTo);
    }

    public Optional<Instant> maxEventTimestamp() {
        return written.stream().map(WriteSet::eventTimestamp).max(Instant::compareTo);
    }

    /** Routes touched by the written messages, for the analytics trigger (DR-35). */
    public Set<String> routeIds() {
        Set<String> routes = new TreeSet<>();
        for (WriteSet w : written) {
            w.vehiclePositions().forEach(r -> routes.add(r.routeId()));
            w.tripUpdates().forEach(r -> routes.add(r.routeId()));
            w.ticketSales().stream().filter(r -> r.routeId() != null).forEach(r -> routes.add(r.routeId()));
        }
        return routes;
    }
}
