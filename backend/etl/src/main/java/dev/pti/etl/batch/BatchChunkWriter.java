package dev.pti.etl.batch;

import dev.pti.common.time.BusinessClock;
import dev.pti.etl.core.WriteSet;
import dev.pti.etl.write.ChunkWriter;
import dev.pti.etl.write.RunMode;
import dev.pti.etl.write.WriteContext;
import dev.pti.etl.write.WriteOutcome;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;

/** The shared {@link ChunkWriter} as a Spring Batch writer, in the chunk transaction Spring Batch opened (DOC-19 §4.3). */
public class BatchChunkWriter implements ItemWriter<WriteSet> {

    private final ChunkWriter writer;
    private final BusinessClock clock;
    private final Supplier<StepExecution> step;

    public BatchChunkWriter(ChunkWriter writer, BusinessClock clock) {
        this(writer, clock, StepValues::current);
    }

    BatchChunkWriter(ChunkWriter writer, BusinessClock clock, Supplier<StepExecution> step) {
        this.writer = writer;
        this.clock = clock;
        this.step = step;
    }

    @Override
    public void write(Chunk<? extends WriteSet> chunk) {
        StepExecution execution = step.get();
        WriteContext context = new WriteContext(
                StepValues.batchId(execution), RunMode.BATCH, StepValues.replay(execution), clock.instant());
        WriteOutcome outcome = writer.write(List.copyOf(chunk.getItems()), context);
        StepValues.addRejected(execution, outcome.rejected());
    }
}
