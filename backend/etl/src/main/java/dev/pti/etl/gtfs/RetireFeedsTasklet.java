package dev.pti.etl.gtfs;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Step {@code retire} (DOC-21 §5): keeps the newest {@code keepVersions} ACTIVE/RETIRED versions and removes older
 * RETIRED ones, plus REJECTED and orphan STAGED versions past {@code rejectedRetention}. One batch of at most
 * {@link #BATCH} rows per call, children first, so no transaction is long. The raw zone zip stays.
 */
public class RetireFeedsTasklet implements Tasklet {

    static final int BATCH = 50_000;
    static final String DELETED_KEY = "pti.gtfs.retire.deleted";

    private static final String VICTIM = """
            SELECT v.feed_version_id FROM dw.gtfs_feed_version v
            WHERE (v.status = 'RETIRED' AND v.feed_version_id NOT IN (
                       SELECT k.feed_version_id FROM dw.gtfs_feed_version k
                       WHERE k.status IN ('ACTIVE', 'RETIRED')
                       ORDER BY k.activated_at DESC NULLS LAST, k.feed_version_id DESC
                       LIMIT ?))
               OR (v.status IN ('REJECTED', 'STAGED') AND v.loaded_at < now() - make_interval(secs => ?)
                   AND v.feed_version_id <> ?)
            ORDER BY v.feed_version_id
            LIMIT 1
            """;

    private final JdbcTemplate jdbc;
    private final FeedVersions versions;
    private final int keepVersions;
    private final Duration rejectedRetention;
    private final MeterRegistry meters;

    public RetireFeedsTasklet(
            JdbcTemplate jdbc,
            FeedVersions versions,
            int keepVersions,
            Duration rejectedRetention,
            MeterRegistry meters) {
        this.jdbc = jdbc;
        this.versions = versions;
        this.keepVersions = keepVersions;
        this.rejectedRetention = rejectedRetention;
        this.meters = meters;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        var context = contribution.getStepExecution().getExecutionContext();
        long current = FeedContext.feedVersionId(contribution.getStepExecution().getJobExecution());
        List<Long> victim =
                jdbc.queryForList(VICTIM, Long.class, keepVersions, (double) rejectedRetention.toSeconds(), current);
        if (victim.isEmpty()) {
            contribution.setExitStatus(ExitStatus.COMPLETED.addExitDescription(
                    "Deleted " + context.getLong(DELETED_KEY, 0L) + " rows of old feed versions"));
            return RepeatStatus.FINISHED;
        }
        FeedVersions.Purged purged = versions.purgeBatch(victim.getFirst(), BATCH);
        context.putLong(DELETED_KEY, context.getLong(DELETED_KEY, 0L) + purged.rows());
        contribution.incrementWriteCount(purged.rows());
        Counter.builder("pti.retention.deleted")
                .tag("table", purged.table())
                .register(meters)
                .increment(purged.rows());
        return RepeatStatus.CONTINUABLE;
    }
}
