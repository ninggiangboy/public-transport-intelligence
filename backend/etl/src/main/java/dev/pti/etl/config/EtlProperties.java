package dev.pti.etl.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code pti.etl.*} (DOC-29 §3.3, DOC-19 §9, DOC-20 §11). Defaults are in {@code application.yml}. */
@ConfigurationProperties("pti.etl")
@Validated
public record EtlProperties(
        @NotNull @Valid Consumer consumer,
        @NotNull @Valid Retry retry,
        @NotNull @Valid Reference reference,
        @NotNull @Valid Health health,
        @NotNull @Valid KnownKeyCache knownKeyCache,
        @NotNull @Valid Batch batch,
        @NotNull @Valid Dedup dedup,
        @NotNull @Valid Baseline baseline) {

    public record Consumer(
            @Min(1) int maxPollRecords,
            @NotNull Duration fetchMaxWait,
            @Min(1) int fetchMinBytes) {}

    /**
     * Back-off for infrastructure errors: streaming retries forever up to {@code maxInterval} between attempts; batch
     * steps give up after {@code maxAttempts} or {@code maxElapsed}.
     */
    public record Retry(
            @NotNull Duration initialInterval,
            @DecimalMin("1.0") double multiplier,
            @NotNull Duration maxInterval,
            @Min(1) int maxAttempts,
            @NotNull Duration maxElapsed) {}

    public record Reference(
            @NotNull Duration refreshInterval, @Min(0) int extraDays) {}

    public record Health(
            @NotNull Duration freshness, @NotNull URI connectUrl) {}

    public record KnownKeyCache(@Min(1) int maxSize) {}

    public record Batch(
            @Min(1) int chunkSize,
            @DecimalMin("0") @DecimalMax("1") double maxSkipRatio,
            @Min(1) int skipMinSample) {}

    public record Dedup(boolean enabled, @NotNull Duration ttl) {}

    /** DR-27: only the {@code experiment} profile may change these. */
    public record Baseline(
            @NotNull OffsetCommit offsetCommit,
            @NotNull ErrorMode errorMode,
            @NotNull WriteMode writeMode,
            @NotNull DedupMode dedup) {

        public boolean isDefault() {
            return offsetCommit == OffsetCommit.MANUAL
                    && errorMode == ErrorMode.SKIP
                    && writeMode == WriteMode.UPSERT
                    && dedup == DedupMode.ON;
        }
    }

    public enum OffsetCommit {
        MANUAL,
        AUTO
    }

    public enum ErrorMode {
        SKIP,
        FAIL_BATCH
    }

    public enum WriteMode {
        UPSERT,
        INSERT
    }

    public enum DedupMode {
        ON,
        OFF
    }
}
