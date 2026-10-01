package dev.pti.api.sim.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code pti.api.sim.base-url} (DOC-32 E-90): where the control API of the simulator is. */
@ConfigurationProperties("pti.api.sim")
@Validated
public record SimProperties(@NotBlank String baseUrl) {}
