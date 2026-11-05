package dev.pti.etl.gtfs.adapter.out.jdbc;

import dev.pti.etl.gtfs.application.port.FeedVersionStore;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@link FeedVersionStore} on {@code dw.gtfs_feed_version} and the removal of a version's rows. */
public class FeedVersions implements FeedVersionStore {

    /** Tables of a version, children first (DOC-21 §5). */
    static final List<String> PURGE_ORDER = List.of(
            "dw.gtfs_stop_time",
            "dw.gtfs_trip",
            "dw.route_headway",
            "dw.gtfs_shape",
            "dw.gtfs_calendar_date",
            "dw.gtfs_calendar",
            "dw.dim_stop",
            "dw.dim_route",
            "dw.dim_agency",
            "dw.gtfs_feed_version");

    private final JdbcTemplate jdbc;

    public FeedVersions(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Existing> findByHash(String hash) {
        return jdbc
                .query(
                        "SELECT feed_version_id, status, job_execution_id FROM dw.gtfs_feed_version WHERE feed_hash = ?",
                        (rs, n) -> new Existing(rs.getLong(1), rs.getString(2), rs.getObject(3, Long.class)),
                        hash)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<String> status(long id) {
        return jdbc
                .queryForList("SELECT status FROM dw.gtfs_feed_version WHERE feed_version_id = ?", String.class, id)
                .stream()
                .findFirst();
    }

    @Override
    public OptionalLong activeId() {
        List<Long> ids = jdbc.queryForList(
                "SELECT feed_version_id FROM dw.gtfs_feed_version WHERE status = 'ACTIVE'", Long.class);
        return ids.isEmpty() ? OptionalLong.empty() : OptionalLong.of(ids.getFirst());
    }

    @Override
    public OptionalLong insert(
            String hash,
            String sourceUri,
            String rawObjectKey,
            String agencyTimezone,
            String status,
            @Nullable String report,
            long jobExecutionId) {
        List<Long> ids = jdbc.queryForList(
                """
                INSERT INTO dw.gtfs_feed_version (feed_hash, source_uri, raw_object_key, agency_timezone, status,
                                                  validation_report, job_execution_id)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (feed_hash) DO NOTHING
                RETURNING feed_version_id
                """, Long.class, hash, sourceUri, rawObjectKey, agencyTimezone, status, report, jobExecutionId);
        return ids.isEmpty() ? OptionalLong.empty() : OptionalLong.of(ids.getFirst());
    }

    @Override
    public void setReport(long id, String report) {
        jdbc.update(
                "UPDATE dw.gtfs_feed_version SET validation_report = ?::jsonb WHERE feed_version_id = ?", report, id);
    }

    /** Appends warnings found after activation (loadVehicles) to the report. */
    @Override
    public void appendWarnings(long id, String warningsJsonArray) {
        jdbc.update("""
                UPDATE dw.gtfs_feed_version
                SET validation_report = jsonb_set(coalesce(validation_report, '{}'::jsonb), '{warnings}',
                        coalesce(validation_report -> 'warnings', '[]'::jsonb) || ?::jsonb)
                WHERE feed_version_id = ?
                """, warningsJsonArray, id);
    }

    @Override
    public void reject(long id) {
        jdbc.update("UPDATE dw.gtfs_feed_version SET status = 'REJECTED' WHERE feed_version_id = ?", id);
    }

    @Override
    public void feedInfo(long id, @Nullable String publisherName, @Nullable String publisherVersion) {
        jdbc.update("""
                UPDATE dw.gtfs_feed_version SET publisher_name = ?, publisher_feed_version = ?
                WHERE feed_version_id = ?
                """, publisherName, publisherVersion, id);
    }

    /**
     * Deletes at most {@code limit} rows of the version from the first table that still has some, children first.
     *
     * @return the table and the number of rows deleted; 0 rows once the version is gone
     */
    @Override
    public Purged purgeBatch(long id, int limit) {
        for (String table : PURGE_ORDER) {
            int deleted = jdbc.update(
                    "DELETE FROM " + table + " WHERE ctid IN (SELECT ctid FROM " + table
                            + " WHERE feed_version_id = ? LIMIT ?)",
                    id,
                    limit);
            if (deleted > 0) {
                return new Purged(table, deleted);
            }
        }
        return new Purged("", 0);
    }

    /** Every row of the version, in one go; for an orphan STAGED version found by {@code fetch}. */
    @Override
    public void purgeAll(long id) {
        while (purgeBatch(id, Integer.MAX_VALUE).rows() > 0) {
            // next table
        }
    }
}
