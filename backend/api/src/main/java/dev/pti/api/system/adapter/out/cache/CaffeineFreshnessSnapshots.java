package dev.pti.api.system.adapter.out.cache;

import com.github.benmanes.caffeine.cache.Cache;
import dev.pti.api.platform.adapter.out.cache.ApiCaches;
import dev.pti.api.system.application.port.FreshnessSnapshots;
import dev.pti.api.system.domain.FreshnessSnapshot;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The probe result in the one-entry {@code freshness} cache (DOC-31 §10.3), so it is measured like the others. A
 * component rather than a {@code @Bean}: its constructor names {@link ApiCaches}, an adapter of the platform, which a
 * {@code config} class of this feature may not reference (A-14).
 */
@Component
public final class CaffeineFreshnessSnapshots implements FreshnessSnapshots {

    private static final String KEY = "current";

    private final Cache<String, FreshnessSnapshot> cache;

    public CaffeineFreshnessSnapshots(ApiCaches caches) {
        this.cache = caches.cache("freshness");
    }

    @Override
    public Optional<FreshnessSnapshot> current() {
        return Optional.ofNullable(cache.getIfPresent(KEY));
    }

    @Override
    public void store(FreshnessSnapshot snapshot) {
        cache.put(KEY, snapshot);
    }
}
