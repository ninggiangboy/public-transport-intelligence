package dev.pti.api.transit.domain;

import java.time.Duration;

/** {@code pti.analytics.otp.early-tolerance} and {@code .late-tolerance}: the on-time window of OTP (DOC-23 §8.1). */
public record OnTimeTolerance(Duration early, Duration late) {}
