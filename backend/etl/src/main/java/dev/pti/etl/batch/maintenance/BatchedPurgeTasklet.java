package dev.pti.etl.batch.maintenance;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Deletes expired rows in batches of {@code batchSize}, one batch per transaction (DOC-18 §5): each call deletes one
 * batch and returns {@code CONTINUABLE}, so Spring Batch commits it together with the step context that records
 * the progress, and no delete holds locks for long (test L-03).
 */
public class BatchedPurgeTasklet implements Tasklet {

    /**
     * One table to purge.
     *
     * @param condition a {@code WHERE} condition with one {@code ?}, the cutoff; it must match only expired rows
     */
    public record PurgeTarget(String table, String condition, Duration retention) {

        String sql() {
            return "DELETE FROM " + table + " WHERE ctid IN (SELECT ctid FROM " + table + " WHERE " + condition
                    + " LIMIT ?)";
        }
    }

    static final String TARGET_KEY = "pti.purge.target";
    static final String DELETED_KEY_PREFIX = "pti.purge.deleted.";

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final List<PurgeTarget> targets;
    private final int batchSize;
    private final RetentionMetrics metrics;

    /** @param clock real time: every purged column is an audit timestamp (DR-67) */
    public BatchedPurgeTasklet(
            JdbcTemplate jdbc, Clock clock, List<PurgeTarget> targets, int batchSize, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.targets = List.copyOf(targets);
        this.batchSize = batchSize;
        this.metrics = new RetentionMetrics(meters);
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        ExecutionContext context = contribution.getStepExecution().getExecutionContext();
        int index = context.getInt(TARGET_KEY, 0);
        if (index >= targets.size()) {
            contribution.setExitStatus(ExitStatus.COMPLETED.addExitDescription(summary(context)));
            return RepeatStatus.FINISHED;
        }
        PurgeTarget target = targets.get(index);
        OffsetDateTime cutoff =
                OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).minus(target.retention());
        int deleted = jdbc.update(target.sql(), cutoff, batchSize);
        String key = DELETED_KEY_PREFIX + target.table();
        context.putLong(key, context.getLong(key, 0L) + deleted);
        contribution.incrementWriteCount(deleted);
        metrics.deleted(target.table(), deleted);
        if (deleted < batchSize) {
            context.putInt(TARGET_KEY, index + 1);
        }
        return RepeatStatus.CONTINUABLE;
    }

    private String summary(ExecutionContext context) {
        List<String> parts = new ArrayList<>();
        for (PurgeTarget t : targets) {
            parts.add(t.table() + ": " + context.getLong(DELETED_KEY_PREFIX + t.table(), 0L));
        }
        return "Deleted rows; " + String.join("; ", parts);
    }
}
