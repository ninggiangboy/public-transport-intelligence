package dev.pti.etl.gtfs.application.port;

import java.util.Optional;
import java.util.OptionalLong;
import org.jspecify.annotations.Nullable;

/** {@code dw.gtfs_feed_version} and the removal of a version's rows (DOC-14 §4, DOC-21 §3.1, §5). */
public interface FeedVersionStore {

    /** A version found by its hash. */
    record Existing(long id, String status, @Nullable Long jobExecutionId) {}

    /** The rows one {@code purgeBatch} call removed. */
    record Purged(String table, int rows) {}

    Optional<Existing> findByHash(String hash);

    Optional<String> status(long id);

    OptionalLong activeId();

    /**
     * Inserts a version; empty when another execution inserted the same hash first (DOC-21 §3.1 step 7).
     *
     * @param report the validation report as JSON, for a feed rejected by {@code fetch}
     */
    OptionalLong insert(
            String hash,
            String sourceUri,
            String rawObjectKey,
            String agencyTimezone,
            String status,
            @Nullable String report,
            long jobExecutionId);

    void setReport(long id, String report);

    /** Appends warnings found after activation (loadVehicles) to the report. */
    void appendWarnings(long id, String warningsJsonArray);

    void reject(long id);

    void feedInfo(long id, @Nullable String publisherName, @Nullable String publisherVersion);

    /** Deletes at most {@code limit} rows of the version from the first table that still has some, children first. */
    Purged purgeBatch(long id, int limit);

    /** Every row of the version, in one go; for an orphan STAGED version found by {@code fetch}. */
    void purgeAll(long id);
}
