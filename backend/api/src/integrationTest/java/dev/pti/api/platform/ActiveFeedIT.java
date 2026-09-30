package dev.pti.api.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.pti.api.ApiIntegrationSupport;
import dev.pti.api.platform.adapter.out.cache.ApiCaches;
import dev.pti.api.platform.application.RequireActiveFeed;
import dev.pti.api.platform.application.port.ActiveFeedReader;
import dev.pti.api.platform.domain.ActiveFeed;
import dev.pti.api.platform.domain.ServiceUnavailableException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The ACTIVE feed lookup (DOC-31 §10.2, §10.3) against the real {@code dw.gtfs_feed_version}. */
class ActiveFeedIT extends ApiIntegrationSupport {

    @Autowired
    private ActiveFeedReader feeds;

    @Autowired
    private RequireActiveFeed requireActiveFeed;

    @Autowired
    private ApiCaches caches;

    @BeforeEach
    @AfterEach
    void clean() {
        asOwner("DELETE FROM dw.gtfs_feed_version");
        forgetCachedLookups();
    }

    /**
     * The cache also keeps "no feed" for its TTL, and background lookups (the freshness probe) can fill it between
     * {@link #clean()} and the test's inserts, so each test drops it again once its feeds are in place.
     */
    private void forgetCachedLookups() {
        caches.cache("active-feed").invalidateAll();
    }

    @Test
    @DisplayName("AG-19 without an ACTIVE feed the lookup is empty and the use case is a 503 with Retry-After 30")
    void noActiveFeed() {
        assertThat(feeds.find()).isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(requireActiveFeed::execute)
                .isInstanceOfSatisfying(ServiceUnavailableException.class, e -> {
                    assertThat(e.retryAfterSeconds()).isEqualTo(30);
                    assertThat(e.getMessage()).isEqualTo("No active GTFS feed yet.");
                });
    }

    @Test
    @DisplayName("The ACTIVE feed comes with its id, publisher version, timezone, validity and activation time")
    void readsTheActiveFeed() {
        asOwner(
                activeFeedSql("a", "2026-08-23"),
                // A feed that is not ACTIVE is never returned.
                """
                INSERT INTO dw.gtfs_feed_version (feed_hash, source_uri, raw_object_key, agency_timezone, status)
                VALUES (repeat('b', 64), 'file:///old.zip', 'raw/gtfs-static/b.zip', 'America/Chicago', 'STAGED')""");
        forgetCachedLookups();

        ActiveFeed feed = feeds.find().orElseThrow();

        assertThat(feed.feedVersionId()).isPositive();
        assertThat(feed.publisherFeedVersion()).isEqualTo("2026-08-23");
        assertThat(feed.timezone()).isEqualTo(ZoneId.of("America/Chicago"));
        assertThat(feed.validFrom()).isEqualTo(LocalDate.parse("2026-08-23"));
        assertThat(feed.validTo()).isEqualTo(LocalDate.parse("2026-12-12"));
        assertThat(feed.activatedAt()).isEqualTo(Instant.parse("2026-09-27T08:34:40Z"));
        assertThat(requireActiveFeed.execute()).isEqualTo(feed);
    }

    @Test
    @DisplayName(
            "AG-18 when the ACTIVE feed changes, the lookup follows within the TTL and the GTFS caches are cleared")
    void feedChangeClearsTheGtfsCaches() {
        asOwner(activeFeedSql("a", "2026-08-23"));
        forgetCachedLookups();
        long first = feeds.find().orElseThrow().feedVersionId();
        caches.<String, String>cache("routes").put(first + ":all", "routes of the old feed");
        caches.<String, String>cache("route-detail").put(first + ":18", "detail of the old feed");
        caches.<String, String>cache("arrivals").put("51405", "live arrivals");

        asOwner(
                "UPDATE dw.gtfs_feed_version SET status = 'RETIRED', retired_at = now() WHERE status = 'ACTIVE'",
                activeFeedSql("c", "2026-09-30"));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(feeds.find().orElseThrow().feedVersionId()).isGreaterThan(first);
        });
        assertThat(caches.<String, String>cache("routes").getIfPresent(first + ":all"))
                .isNull();
        assertThat(caches.<String, String>cache("route-detail").getIfPresent(first + ":18"))
                .isNull();
        assertThat(caches.<String, String>cache("arrivals").getIfPresent("51405"))
                .isEqualTo("live arrivals");
    }
}
