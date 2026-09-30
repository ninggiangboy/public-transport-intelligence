package dev.pti.analytics.otp.domain;

import java.time.Duration;

/**
 * The tunable values of the on-time performance scorecard (DOC-23 §13, {@code pti.analytics.otp.*}).
 *
 * @param earlyTolerance an arrival up to this much early is on time (FR-08.2)
 * @param lateTolerance an arrival up to this much late is on time
 * @param recomputeDays how many days back the nightly job recomputes
 */
public record OtpSettings(Duration earlyTolerance, Duration lateTolerance, int recomputeDays) {}
