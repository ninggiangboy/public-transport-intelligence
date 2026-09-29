package dev.pti.etl.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code pti.dq.*} (DOC-16 §6). */
@ConfigurationProperties("pti.dq")
@Validated
public record DqProperties(
        @NotNull Duration maxClockSkew,
        @NotNull Duration maxDelay,
        @DecimalMin("0") double bboxMargin,
        @NotNull BigDecimal maxTicketAmount,
        @NotNull Duration refundGrace,
        @NotNull @Valid PostWrite postWrite,
        Map<String, Rule> rules) {

    public DqProperties {
        rules = rules == null ? Map.of() : Map.copyOf(rules);
    }

    public record PostWrite(boolean enabled, @NotNull Duration statementTimeout) {}

    public record Rule(boolean enabled) {}

    /** {@code pti.dq.rules.<ID>.enabled}; DQ-01 cannot be turned off. */
    public boolean enabled(String ruleId) {
        if ("DQ-01".equals(ruleId)) {
            return true;
        }
        Rule rule = rules.get(ruleId);
        return rule == null || rule.enabled();
    }
}
