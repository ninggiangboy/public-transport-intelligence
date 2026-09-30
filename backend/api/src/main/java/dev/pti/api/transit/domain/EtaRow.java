package dev.pti.api.transit.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** One row of {@code insight.insight_eta_prediction}: the historical delay at a stop for a weekday and hour. */
public record EtaRow(
        String stopId,
        BigDecimal avgDelaySeconds,
        int medianDelaySeconds,
        int p90DelaySeconds,
        int sampleCount,
        LocalDate windowStart,
        LocalDate windowEnd,
        Instant computedAt) {}
