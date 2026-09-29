package dev.pti.etl.replay;

import dev.pti.common.time.BusinessClock;
import dev.pti.etl.batch.StepValues;
import dev.pti.etl.core.WriteSet;
import dev.pti.etl.write.ChunkWriter;
import dev.pti.etl.write.RunMode;
import dev.pti.etl.write.WriteContext;
import dev.pti.etl.write.WriteOutcome;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;

/**
 * Writer of {@code DlqReplayJob} (DOC-22 §3.2 step 4): the facts with replay semantics, then the dead letter becomes
 * {@code REPLAYED}, both in the chunk transaction. A chunk rule rejection has already refreshed the dead letter.
 */
public class DlqReplayWriter implements ItemWriter<WriteSet> {

    private final ChunkWriter writer;
    private final DeadLetterReplays deadLetters;
    private final BusinessClock clock;
    private final Supplier<StepExecution> step;

    public DlqReplayWriter(ChunkWriter writer, DeadLetterReplays deadLetters, BusinessClock clock) {
        this(writer, deadLetters, clock, StepValues::current);
    }

    DlqReplayWriter(
            ChunkWriter writer, DeadLetterReplays deadLetters, BusinessClock clock, Supplier<StepExecution> step) {
        this.writer = writer;
        this.deadLetters = deadLetters;
        this.clock = clock;
        this.step = step;
    }

    @Override
    public void write(Chunk<? extends WriteSet> chunk) {
        if (chunk.isEmpty()) {
            // Spring Batch still calls the writer when the item was skipped in process; nothing was replayed.
            return;
        }
        StepExecution execution = step.get();
        UUID batchId = StepValues.batchId(execution);
        WriteOutcome outcome = writer.write(
                List.copyOf(chunk.getItems()), new WriteContext(batchId, RunMode.BATCH, true, clock.instant()));
        StepValues.addRejected(execution, outcome.rejected());
        if (outcome.rejected() > 0) {
            return;
        }
        UUID deadLetterId = UUID.fromString(execution.getExecutionContext().getString(DeadLetterReader.DEAD_LETTER_ID));
        UUID request =
                UUID.fromString(String.valueOf(execution.getJobParameters().getString("replayRequestId")));
        deadLetters.markReplayed(deadLetterId, request, batchId, outcome.writtenCount());
    }
}
