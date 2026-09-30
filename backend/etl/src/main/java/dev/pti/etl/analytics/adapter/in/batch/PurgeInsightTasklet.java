package dev.pti.etl.analytics.adapter.in.batch;

import dev.pti.analytics.retention.application.PurgeInsight;
import dev.pti.analytics.retention.application.PurgeInsightRequest;
import dev.pti.analytics.retention.domain.RetentionTarget;
import java.util.ArrayList;
import java.util.List;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

/**
 * Step {@code purgeInsight} of {@code OpsRetentionJob} (DOC-23 §12.3, DOC-18 §5): each call deletes one batch of one
 * {@code insight} table and returns {@code CONTINUABLE}, so Spring Batch commits it together with the progress in the
 * step context and no delete holds locks for long. A table that returns a full batch is asked again; a short batch
 * moves on to the next table.
 */
public class PurgeInsightTasklet implements Tasklet {

    static final String TARGET_KEY = "pti.purge.target";
    static final String DELETED_KEY_PREFIX = "pti.purge.deleted.";

    private final PurgeInsight purge;
    private final int batchSize;

    public PurgeInsightTasklet(PurgeInsight purge, int batchSize) {
        this.purge = purge;
        this.batchSize = batchSize;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        ExecutionContext context = contribution.getStepExecution().getExecutionContext();
        int index = context.getInt(TARGET_KEY, 0);
        RetentionTarget[] targets = RetentionTarget.values();
        if (index >= targets.length) {
            contribution.setExitStatus(ExitStatus.COMPLETED.addExitDescription(summary(context)));
            return RepeatStatus.FINISHED;
        }
        RetentionTarget target = targets[index];
        int deleted = purge.execute(new PurgeInsightRequest(target, batchSize));
        String key = DELETED_KEY_PREFIX + target.table();
        context.putLong(key, context.getLong(key, 0L) + deleted);
        contribution.incrementWriteCount(deleted);
        if (deleted < batchSize) {
            context.putInt(TARGET_KEY, index + 1);
        }
        return RepeatStatus.CONTINUABLE;
    }

    private static String summary(ExecutionContext context) {
        List<String> parts = new ArrayList<>();
        for (RetentionTarget target : RetentionTarget.values()) {
            parts.add(target.table() + ": " + context.getLong(DELETED_KEY_PREFIX + target.table(), 0L));
        }
        return "Deleted rows; " + String.join("; ", parts);
    }
}
