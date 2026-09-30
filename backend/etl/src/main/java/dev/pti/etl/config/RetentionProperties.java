package dev.pti.etl.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code pti.retention.*} (DOC-18 §1). */
@ConfigurationProperties("pti.retention")
@Validated
public record RetentionProperties(
        @NotNull Duration vehiclePosition,
        @NotNull Duration tripUpdate,
        @NotNull Duration ticketSales,
        @NotNull Duration etlStreamBatch,
        @NotNull Duration deadLetterResolved,
        @NotNull Duration replayRequest,
        @NotNull Duration jobRequest,
        @NotNull Duration dqCheckResult,
        @NotNull Duration alertEvent,
        @NotNull Duration batchMetadata,
        @NotNull Duration insight,
        @NotNull Duration baselineSnapshot) {}
