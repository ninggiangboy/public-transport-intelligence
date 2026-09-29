package dev.pti.etl.write;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.Collection;
import java.util.List;

/**
 * Dimension keys known to be committed (DOC-20 §4.5), so that placeholder inserts for {@code dim_vehicle} and
 * {@code dim_sale_point} are not repeated every chunk. Keys are added only after commit; losing the cache on restart
 * costs a few {@code DO NOTHING} inserts.
 */
public final class KnownKeyCache {

    private final Cache<String, Boolean> vehicles;
    private final Cache<String, Boolean> salePoints;

    public KnownKeyCache(int maxSize) {
        this.vehicles = Caffeine.newBuilder().maximumSize(maxSize).build();
        this.salePoints = Caffeine.newBuilder().maximumSize(maxSize).build();
    }

    public List<String> unknownVehicles(Collection<String> ids) {
        return unknown(vehicles, ids);
    }

    public List<String> unknownSalePoints(Collection<String> ids) {
        return unknown(salePoints, ids);
    }

    public void addVehiclesAfterCommit(Collection<String> ids) {
        addAfterCommit(vehicles, List.copyOf(ids));
    }

    public void addSalePointsAfterCommit(Collection<String> ids) {
        addAfterCommit(salePoints, List.copyOf(ids));
    }

    private static List<String> unknown(Cache<String, Boolean> cache, Collection<String> ids) {
        return ids.stream()
                .filter(id -> cache.getIfPresent(id) == null)
                .distinct()
                .sorted()
                .toList();
    }

    private static void addAfterCommit(Cache<String, Boolean> cache, List<String> ids) {
        if (!ids.isEmpty()) {
            AfterCommit.run(() -> ids.forEach(id -> cache.put(id, Boolean.TRUE)));
        }
    }
}
