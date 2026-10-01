package dev.pti.api.insight.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code pti.api.dispatch.*} (DOC-32 E-17): a dispatch suggestion whose action confidence is below {@code
 * lowConfidence} is shown as "Low confidence" (FR-09.6).
 */
@ConfigurationProperties("pti.api.dispatch")
@Validated
public record DispatchProperties(
        @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal lowConfidence) {}
