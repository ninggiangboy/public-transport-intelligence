package dev.pti.api.platform.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * The {@code pti.api.*} keys of the platform: DOC-31 §14 (paging, time range, cache, rate limit, body size) and DOC-27
 * §13 (security, webhook). The keys of a feature ({@code pti.api.vehicles.*}, {@code pti.api.freshness.*}, …) are
 * bound by that feature's own config. A wrong value stops the application at start (DOC-29 §1).
 */
@ConfigurationProperties("pti.api")
@Validated
public record ApiProperties(
        @NotNull @Valid Paging paging,
        @NotNull @Valid Time time,
        @NotNull Map<String, CacheSpec> cache,
        @NotNull @Valid RateLimit rateLimit,
        @NotNull DataSize maxBodySize,
        @NotNull @Valid Security security,
        @NotNull @Valid AlertWebhook alertWebhook) {

    /** {@code pti.api.paging.*} (DOC-31 §5.1). */
    public record Paging(
            @Min(1) int defaultLimit, @Min(1) @Max(10_000) int maxLimit) {}

    /** {@code pti.api.time.*} (DOC-31 §4.3). */
    public record Time(@NotNull Duration maxRange, @NotNull Duration opsMaxRange) {}

    /** {@code pti.api.cache.<name>.*} (DOC-31 §10.3); no {@code ttl} means the entries do not expire by time. */
    public record CacheSpec(@Nullable Duration ttl, @Min(1) int maxSize) {}

    /** {@code pti.api.rate-limit.*} (DOC-31 §11). */
    public record RateLimit(
            boolean enabled,
            @Min(1) int publicPerMinute,
            @Min(1) int authenticatedPerMinute,
            @Min(1) int writePerMinute,
            @Min(1) int ssePerIp,
            @Min(1) int ssePerUser) {}

    /** {@code pti.api.security.*} (DOC-27 §3.2, §3.3). */
    public record Security(
            @NotBlank String issuer,
            @NotBlank String audience,
            @NotNull Duration clockSkew,
            @Nullable StaticJwt staticJwt) {}

    /** {@code pti.api.security.static-jwt.*}: only the {@code static-jwt} profile reads it. */
    public record StaticJwt(@Nullable Path publicKeyFile) {}

    /** {@code pti.api.alert-webhook.*} (DOC-27 §6). */
    public record AlertWebhook(@NotNull Path tokenFile) {}
}
