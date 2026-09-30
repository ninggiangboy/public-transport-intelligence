package dev.pti.analytics.otp.application.port;

import dev.pti.analytics.otp.domain.OtpTolerances;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The warehouse side of the OTP scorecard (DOC-23 §8.1): one SQL statement scores every route of a service date where
 * the data is. It runs in the transaction of the caller.
 */
public interface OtpScorecardStore {

    /** What one day's merge did, for the log and the recompute statistics (DOC-23 §11.7). */
    record Merge(int upserted, int deleted) {}

    /**
     * Scores the observed arrivals of the service date and merges the rows (DOC-23 §2.4): a route with arrivals is
     * upserted, a route of the date without any is deleted.
     */
    Merge recompute(LocalDate serviceDate, OtpTolerances tolerances, Instant computedAt, UUID batchId);
}
