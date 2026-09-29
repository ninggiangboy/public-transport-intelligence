package dev.pti.etl.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/** {@code pti.gtfs.*} (DOC-21 §7). */
@ConfigurationProperties("pti.gtfs")
@Validated
public record GtfsProperties(
        String bootstrapLocation,
        @Name("static") @NotNull @Valid Static staticFeed) {

    public GtfsProperties {
        bootstrapLocation = bootstrapLocation == null ? "" : bootstrapLocation.strip();
    }

    public record Static(
            String source,
            @NotNull String cron,
            @NotNull ZoneId zone,
            @NotNull List<Path> allowedDirs,
            boolean allowHttp,
            String expectedSha256,
            @NotNull Path workDir,
            @NotNull DataSize maxUncompressedSize,
            @Min(1) int maxEntries,
            @Min(1) int maxRowErrors,
            @Min(1) int reportSamples,
            @Min(1) int keepVersions,
            @NotNull Duration rejectedRetention) {

        public Static {
            source = source == null ? "" : source.strip();
            expectedSha256 = expectedSha256 == null ? "" : expectedSha256.strip();
        }
    }
}
