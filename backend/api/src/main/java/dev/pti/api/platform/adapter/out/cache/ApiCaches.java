package dev.pti.api.platform.adapter.out.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import dev.pti.api.platform.application.port.FeedChangeListener;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The Caffeine caches of the API (DOC-31 §10.3): one per name, sized and expiring from configuration, each reporting
 * {@code cache_*} metrics tagged with its name. They live in the memory of the pod (DR-103): a cache is an adapter
 * detail, reached by features through their own ports.
 *
 * <p>Load through {@code cache.get(key, loader)}: Caffeine then runs one loader per key at a time, which is the
 * single-flight loading that {@code vehicles-live}, {@code arrivals} and {@code public-disruptions} need.
 */
public final class ApiCaches implements FeedChangeListener {

    /** The caches whose entries belong to one GTFS feed; they are cleared when the ACTIVE feed changes. */
    static final Set<String> GTFS_SCOPED =
            Set.of("routes", "route-detail", "stop-detail", "stops-search", "stop-routes");

    /** Size and lifetime of one cache; no {@code ttl} means entries stay until evicted or cleared. */
    public record Spec(@Nullable Duration ttl, long maxSize) {}

    private final Map<String, Cache<Object, Object>> caches = new LinkedHashMap<>();

    public ApiCaches(Map<String, Spec> specs, MeterRegistry registry) {
        this(specs, registry, Ticker.systemTicker());
    }

    /** With a ticker of the caller's choice, so a test can move time. */
    public ApiCaches(Map<String, Spec> specs, MeterRegistry registry, Ticker ticker) {
        specs.forEach((name, spec) -> {
            Caffeine<Object, Object> builder = Caffeine.newBuilder()
                    .maximumSize(spec.maxSize())
                    .ticker(ticker)
                    .recordStats();
            if (spec.ttl() != null) {
                builder.expireAfterWrite(spec.ttl());
            }
            Cache<Object, Object> cache = builder.build();
            CaffeineCacheMetrics.monitor(registry, cache, name);
            caches.put(name, cache);
        });
    }

    /**
     * @throws IllegalArgumentException for a name that is not in the configuration
     */
    @SuppressWarnings("unchecked")
    public <K, V> Cache<K, V> cache(String name) {
        Cache<Object, Object> cache = caches.get(name);
        if (cache == null) {
            throw new IllegalArgumentException("No cache named " + name);
        }
        return (Cache<K, V>) cache;
    }

    public Set<String> names() {
        return Set.copyOf(caches.keySet());
    }

    @Override
    public void onFeedChanged(long previousFeedVersionId, long currentFeedVersionId) {
        GTFS_SCOPED.forEach(name -> {
            Cache<Object, Object> cache = caches.get(name);
            if (cache != null) {
                cache.invalidateAll();
            }
        });
    }
}
