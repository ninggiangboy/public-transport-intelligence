package dev.pti.api.platform.adapter.out.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Ticker;
import dev.pti.api.platform.adapter.out.cache.ApiCaches.Spec;
import dev.pti.api.platform.domain.ActiveFeed;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ApiCachesTest {

    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private ApiCaches caches(Map<String, Spec> specs) {
        return new ApiCaches(specs, registry, ticker);
    }

    @Test
    @DisplayName("An entry lives for its ttl and then is loaded again")
    void entriesExpire() {
        ApiCaches caches = caches(Map.of("active-feed", new Spec(Duration.ofSeconds(30), 1)));
        Cache<String, Integer> cache = caches.cache("active-feed");
        AtomicInteger loads = new AtomicInteger();

        cache.get("k", k -> loads.incrementAndGet());
        nanos.addAndGet(Duration.ofSeconds(29).toNanos());
        cache.get("k", k -> loads.incrementAndGet());
        nanos.addAndGet(Duration.ofSeconds(2).toNanos());
        cache.get("k", k -> loads.incrementAndGet());

        assertThat(loads).hasValue(2);
    }

    @Test
    void cacheWithoutTtlKeepsItsEntries() {
        ApiCaches caches = caches(Map.of("stop-routes", new Spec(null, 2)));
        Cache<String, String> cache = caches.cache("stop-routes");

        cache.put("a", "1");
        nanos.addAndGet(Duration.ofDays(30).toNanos());

        assertThat(cache.getIfPresent("a")).isEqualTo("1");
    }

    @Test
    @DisplayName("DOC-31 §10.3 concurrent misses of one key run one loader (single-flight)")
    void singleFlight() throws Exception {
        ApiCaches caches = caches(Map.of("vehicles-live", new Spec(Duration.ofSeconds(2), 256)));
        Cache<String, String> cache = caches.cache("vehicles-live");
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            java.util.List<Future<String>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return cache.get("all", key -> {
                        loads.incrementAndGet();
                        LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
                        return "snapshot";
                    });
                }));
            }
            go.countDown();
            for (Future<String> result : results) {
                assertThat(result.get()).isEqualTo("snapshot");
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(loads).hasValue(1);
    }

    @Test
    void cachesReportMetricsByName() {
        ApiCaches caches = caches(Map.of("routes", new Spec(Duration.ofMinutes(10), 4)));
        Cache<String, String> cache = caches.cache("routes");

        cache.get("k", k -> "v");
        cache.get("k", k -> "v");

        assertThat(registry.get("cache.gets")
                        .tag("cache", "routes")
                        .tag("result", "hit")
                        .functionCounter()
                        .count())
                .isEqualTo(1.0);
        assertThat(registry.get("cache.gets")
                        .tag("cache", "routes")
                        .tag("result", "miss")
                        .functionCounter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void unknownCacheIsAnError() {
        ApiCaches caches = caches(Map.of());

        assertThatIllegalArgumentException().isThrownBy(() -> caches.cache("nope"));
    }

    @Test
    @DisplayName("AG-18 a change of feed clears the GTFS caches and leaves the others")
    void feedChangeClearsGtfsCachesOnly() {
        ApiCaches caches = caches(Map.of(
                "routes", new Spec(Duration.ofMinutes(10), 4),
                "route-detail", new Spec(Duration.ofMinutes(10), 300),
                "arrivals", new Spec(Duration.ofSeconds(5), 5000)));
        caches.<String, String>cache("routes").put("3", "old feed");
        caches.<String, String>cache("route-detail").put("3:18", "old feed");
        caches.<String, String>cache("arrivals").put("51405", "live");

        caches.onFeedChanged(3, 4);

        assertThat(caches.<String, String>cache("routes").getIfPresent("3")).isNull();
        assertThat(caches.<String, String>cache("route-detail").getIfPresent("3:18"))
                .isNull();
        assertThat(caches.<String, String>cache("arrivals").getIfPresent("51405"))
                .isEqualTo("live");
    }

    @Test
    @DisplayName("The active feed is cached, and a refresh that finds a new feedVersionId notifies the listener")
    void activeFeedCacheNotifiesOnChange() {
        ApiCaches caches = caches(Map.of(
                "active-feed", new Spec(Duration.ofSeconds(30), 1),
                "routes", new Spec(Duration.ofMinutes(10), 4)));
        AtomicLong feedVersion = new AtomicLong(3);
        AtomicInteger reads = new AtomicInteger();
        CachingActiveFeedReader reader = new CachingActiveFeedReader(
                () -> {
                    reads.incrementAndGet();
                    return Optional.of(feed(feedVersion.get()));
                },
                caches.cache("active-feed"),
                caches);
        caches.<String, String>cache("routes").put("3", "old feed");

        assertThat(reader.find()).map(ActiveFeed::feedVersionId).contains(3L);
        assertThat(reader.find()).map(ActiveFeed::feedVersionId).contains(3L);
        assertThat(reads).hasValue(1);
        assertThat(caches.<String, String>cache("routes").getIfPresent("3")).isEqualTo("old feed");

        feedVersion.set(4);
        assertThat(reader.find()).map(ActiveFeed::feedVersionId).contains(3L); // still inside the 30 seconds
        nanos.addAndGet(Duration.ofSeconds(31).toNanos());
        assertThat(reader.find()).map(ActiveFeed::feedVersionId).contains(4L);

        assertThat(caches.<String, String>cache("routes").getIfPresent("3")).isNull();
        assertThat(reads).hasValue(2);
    }

    @Test
    void noActiveFeedIsCachedAndDoesNotNotify() {
        ApiCaches caches = caches(Map.of("active-feed", new Spec(Duration.ofSeconds(30), 1)));
        AtomicInteger reads = new AtomicInteger();
        CachingActiveFeedReader reader = new CachingActiveFeedReader(
                () -> {
                    reads.incrementAndGet();
                    return Optional.empty();
                },
                caches.cache("active-feed"),
                (previous, current) -> {
                    throw new AssertionError("no change was expected");
                });

        assertThat(reader.find()).isEmpty();
        assertThat(reader.find()).isEmpty();

        assertThat(reads).hasValue(1);
    }

    private static ActiveFeed feed(long id) {
        return new ActiveFeed(
                id,
                null,
                ZoneId.of("America/Chicago"),
                LocalDate.parse("2026-08-23"),
                null,
                Instant.parse("2026-09-27T08:34:40Z"));
    }
}
