package dev.pti.etl.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code pti.batch.*} (DOC-19 §9). A schedule set to {@code -} is not registered. */
@ConfigurationProperties("pti.batch")
@Validated
public record BatchJobProperties(
        @NotNull Duration staleAfter,
        @NotNull @Valid Executor executor,
        @NotNull @Valid Poller poller,
        @NotNull Map<String, String> schedule) {

    public record Executor(
            @Min(1) int coreSize,
            @Min(1) int maxSize,
            @Min(0) int queueCapacity) {}

    public record Poller(@NotNull Duration interval) {}
}
