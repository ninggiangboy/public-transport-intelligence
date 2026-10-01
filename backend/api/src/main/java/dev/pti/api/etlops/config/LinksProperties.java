package dev.pti.api.etlops.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code pti.api.links.*} (DOC-32 E-32): where the Grafana of the links of a batch is. */
@ConfigurationProperties("pti.api.links")
@Validated
public record LinksProperties(@NotBlank String grafanaUrl) {}
