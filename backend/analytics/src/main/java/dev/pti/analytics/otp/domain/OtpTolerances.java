package dev.pti.analytics.otp.domain;

/**
 * The on-time band of DOC-23 §8.1 in whole seconds, as the statement and the stored rows use it: an arrival is on time
 * when {@code −early ≤ delay ≤ late}, early below the band and late above it. The values are written on every row, so
 * a scorecard says which tolerance it was scored with (FR-08.2).
 */
public record OtpTolerances(int earlySeconds, int lateSeconds) {

    public OtpTolerances {
        if (earlySeconds < 0 || lateSeconds < 0) {
            throw new IllegalArgumentException(
                    "A tolerance must not be negative: " + earlySeconds + ", " + lateSeconds);
        }
    }

    public static OtpTolerances of(OtpSettings settings) {
        return new OtpTolerances(
                Math.toIntExact(settings.earlyTolerance().toSeconds()),
                Math.toIntExact(settings.lateTolerance().toSeconds()));
    }
}
