package dev.pti.etl.write.adapter.out.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.pti.etl.write.adapter.out.jdbc.AfterCommit;
import dev.pti.etl.write.application.port.KnownKeyCache;
import dev.pti.etl.write.domain.KnownKeys;
import java.util.Collection;
import java.util.List;

/** {@link KnownKeyCache} backed by two Caffeine caches, filled after commit (DOC-20 §4.5). */
public final class CaffeineKnownKeyCache implements KnownKeyCache {

    private final Cache<String, Boolean> vehicles;
    private final Cache<String, Boolean> salePoints;

    public CaffeineKnownKeyCache(int maxSize) {
        this.vehicles = Caffeine.newBuilder().maximumSize(maxSize).build();
        this.salePoints = Caffeine.newBuilder().maximumSize(maxSize).build();
    }

    @Override
    public List<String> unknownVehicles(Collection<String> ids) {
        return KnownKeys.unknown(ids, id -> vehicles.getIfPresent(id) != null);
    }

    @Override
    public List<String> unknownSalePoints(Collection<String> ids) {
        return KnownKeys.unknown(ids, id -> salePoints.getIfPresent(id) != null);
    }

    @Override
    public void addVehiclesAfterCommit(Collection<String> ids) {
        add(vehicles, List.copyOf(ids));
    }

    @Override
    public void addSalePointsAfterCommit(Collection<String> ids) {
        add(salePoints, List.copyOf(ids));
    }

    private static void add(Cache<String, Boolean> cache, List<String> ids) {
        if (!ids.isEmpty()) {
            AfterCommit.run(() -> ids.forEach(id -> cache.put(id, Boolean.TRUE)));
        }
    }
}
