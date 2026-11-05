package dev.pti.etl.gtfs.adapter.in.batch;

import dev.pti.common.error.FatalException;
import dev.pti.common.gtfs.GtfsCsv;
import dev.pti.etl.gtfs.application.FeedStructure;
import dev.pti.etl.gtfs.application.port.FeedFiles;
import dev.pti.etl.gtfs.application.port.FeedVersionStore;
import dev.pti.etl.gtfs.application.port.SqlStatements;
import dev.pti.etl.gtfs.domain.FeedStatements;
import dev.pti.etl.gtfs.domain.GtfsTable;
import dev.pti.etl.reference.application.port.ReferenceRefresher;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The short tasklets of {@code GtfsStaticLoadJob} (DOC-21 §2); each is idempotent, so a restart runs it again. */
public final class SimpleFeedTasklets {

    private static final Logger log = LoggerFactory.getLogger(SimpleFeedTasklets.class);

    private SimpleFeedTasklets() {}

    /** {@code loadFeedInfo}: publisher name and version from the optional {@code feed_info.txt}. */
    public static Tasklet loadFeedInfo(FeedFiles workspace, FeedVersionStore versions) {
        return (contribution, chunk) -> {
            JobExecution job = contribution.getStepExecution().getJobExecution();
            Path file = workspace.file(job.getJobInstance().getInstanceId(), GtfsTable.FEED_INFO);
            if (Files.exists(file)) {
                AtomicReference<String[]> first = new AtomicReference<>();
                try (InputStream in = Files.newInputStream(file)) {
                    GtfsCsv.read(
                            FeedStructure.name(file),
                            in,
                            row -> first.compareAndSet(
                                    null, new String[] {row.get("feed_publisher_name"), row.get("feed_version")}));
                } catch (IOException e) {
                    throw new FatalException("Cannot read " + file, e);
                }
                String[] info = first.get();
                if (info != null) {
                    versions.feedInfo(FeedContext.feedVersionId(job), blankToNull(info[0]), blankToNull(info[1]));
                }
            }
            return RepeatStatus.FINISHED;
        };
    }

    /** {@code reject}: the report is already written by {@code validate}. */
    public static Tasklet reject(FeedVersionStore versions) {
        return (contribution, chunk) -> {
            long version =
                    FeedContext.feedVersionId(contribution.getStepExecution().getJobExecution());
            versions.reject(version);
            contribution.setExitStatus(
                    ExitStatus.COMPLETED.addExitDescription("Feed version " + version + " rejected"));
            return RepeatStatus.FINISHED;
        };
    }

    /** {@code finalize}: validity, bbox, {@code route_headway} and the display headway (DOC-14 §6.1), one transaction. */
    public static Tasklet finalizeFeed(NamedParameterJdbcTemplate named, SqlStatements sql) {
        List<String> statements = FeedStatements.split(sql.load("gtfs_finalize"));
        return (contribution, chunk) -> {
            long version =
                    FeedContext.feedVersionId(contribution.getStepExecution().getJobExecution());
            MapSqlParameterSource params = new MapSqlParameterSource("feedVersionId", version);
            named.update("DELETE FROM dw.route_headway WHERE feed_version_id = :feedVersionId", params);
            int headways = 0;
            for (int i = 0; i < statements.size(); i++) {
                int rows = named.update(statements.get(i), params);
                if (i == 1) {
                    headways = rows;
                }
            }
            contribution.setExitStatus(
                    ExitStatus.COMPLETED.addExitDescription(headways + " route_headway rows for version " + version));
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * {@code activate} (DOC-14 §6.2): retire the ACTIVE version and activate this one in one transaction, then
     * refresh the reference data of this JVM at once (DOC-21 §6.2). Already ACTIVE counts as done.
     */
    public static Tasklet activate(
            NamedParameterJdbcTemplate named,
            FeedVersionStore versions,
            ReferenceRefresher refresher,
            SqlStatements sql) {
        List<String> statements = FeedStatements.split(sql.load("gtfs_activate"));
        if (statements.size() != 2) {
            throw new IllegalStateException("gtfs_activate.sql must hold two statements");
        }
        return (contribution, chunk) -> {
            JobExecution job = contribution.getStepExecution().getJobExecution();
            long version = FeedContext.feedVersionId(job);
            if (versions.status(version).filter("ACTIVE"::equals).isPresent()) {
                return RepeatStatus.FINISHED;
            }
            boolean allowRetired =
                    "true".equals(job.getExecutionContext().getString(FetchFeedTasklet.REACTIVATING, ""));
            MapSqlParameterSource params =
                    new MapSqlParameterSource("feedVersionId", version).addValue("allowRetired", allowRetired);
            named.update(statements.get(0), params);
            if (named.update(statements.get(1), params) != 1) {
                throw new FatalException("Feed version " + version + " is no longer STAGED; it cannot be activated");
            }
            log.info("Feed version {} is ACTIVE", version);
            runAfterCommit(refresher::refresh);
            contribution.setExitStatus(ExitStatus.COMPLETED.addExitDescription("Feed version " + version + " ACTIVE"));
            return RepeatStatus.FINISHED;
        };
    }

    /** Runs the action once the surrounding transaction has committed, or at once when there is none. */
    private static void runAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
