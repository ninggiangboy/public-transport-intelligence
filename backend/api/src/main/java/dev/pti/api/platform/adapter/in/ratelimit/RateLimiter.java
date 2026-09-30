package dev.pti.api.platform.adapter.in.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * The per-pod rate limiter of DOC-31 §11 (DR-45): Bucket4j token buckets that refill evenly over the minute, kept in a
 * Caffeine cache that forgets a bucket after ten idle minutes and holds at most 100,000. With N pods the real limit
 * is up to N times higher; that is accepted (ADR-0031).
 */
public final class RateLimiter {

    /** The buckets that apply to REST requests; the SSE connection caps belong to the stream feature. */
    public enum Bucket {
        PUBLIC("public"),
        AUTHENTICATED("authenticated"),
        WRITE("write");

        private final String label;

        Bucket(String label) {
            this.label = label;
        }

        /** The {@code bucket} label of {@code pti_api_rate_limited_total}. */
        public String label() {
            return label;
        }
    }

    /** Requests per minute of each bucket. */
    public record Limits(int publicPerMinute, int authenticatedPerMinute, int writePerMinute) {

        int of(Bucket bucket) {
            return switch (bucket) {
                case PUBLIC -> publicPerMinute;
                case AUTHENTICATED -> authenticatedPerMinute;
                case WRITE -> writePerMinute;
            };
        }
    }

    /**
     * The outcome of taking one token.
     *
     * @param limit requests per minute of the bucket
     * @param remaining tokens left after this request
     * @param retryAfterSeconds when denied, whole seconds until a token is available (at least 1); otherwise 0
     */
    public record Decision(boolean allowed, int limit, long remaining, long retryAfterSeconds) {}

    private static final Duration IDLE = Duration.ofMinutes(10);
    private static final long MAX_BUCKETS = 100_000;

    private final Limits limits;
    private final MeterRegistry registry;
    private final TimeMeter timeMeter;
    private final Cache<String, io.github.bucket4j.Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(IDLE.toMinutes(), TimeUnit.MINUTES)
            .maximumSize(MAX_BUCKETS)
            .build();

    public RateLimiter(Limits limits, MeterRegistry registry) {
        this(limits, registry, TimeMeter.SYSTEM_MILLISECONDS);
    }

    /** With a time source of the caller's choice, so a test can move time. */
    public RateLimiter(Limits limits, MeterRegistry registry, TimeMeter timeMeter) {
        this.limits = limits;
        this.registry = registry;
        this.timeMeter = timeMeter;
    }

    /** Takes one token from the bucket of {@code key} (an IP address or a token subject). */
    public Decision tryConsume(Bucket bucket, String key) {
        int limit = limits.of(bucket);
        io.github.bucket4j.Bucket state = buckets.get(bucket.label() + ":" + key, k -> create(limit));
        ConsumptionProbe probe = state.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return new Decision(true, limit, probe.getRemainingTokens(), 0);
        }
        registry.counter("pti.api.rate.limited", "bucket", bucket.label()).increment();
        long seconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill() + 999_999_999L));
        return new Decision(false, limit, 0, seconds);
    }

    private io.github.bucket4j.Bucket create(int perMinute) {
        Bandwidth bandwidth = Bandwidth.builder()
                .capacity(perMinute)
                .refillGreedy(perMinute, Duration.ofMinutes(1))
                .build();
        return io.github.bucket4j.Bucket.builder()
                .addLimit(bandwidth)
                .withCustomTimePrecision(timeMeter)
                .build();
    }
}
