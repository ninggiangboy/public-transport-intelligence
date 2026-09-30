package dev.pti.api.transit.domain;

import java.math.BigDecimal;

/** The delays observed in one bucket (DOC-32 E-03). The decimals keep the scale the database gives them. */
public record DelayBucket(
        BucketKey key,
        BigDecimal avgDelaySeconds,
        int medianDelaySeconds,
        int p90DelaySeconds,
        long observationCount,
        BigDecimal onTimePercentage) {}
