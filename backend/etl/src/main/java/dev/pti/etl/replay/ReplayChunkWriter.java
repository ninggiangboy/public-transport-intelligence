package dev.pti.etl.replay;

import dev.pti.common.time.BusinessClock;
import dev.pti.etl.batch.StepValues;
import dev.pti.etl.core.InboundMessage;
import dev.pti.etl.core.WriteSet;
import dev.pti.etl.write.ChunkWriter;
import dev.pti.etl.write.RunMode;
import dev.pti.etl.write.WriteContext;
import dev.pti.etl.write.WriteOutcome;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemWriter;

/**
 * Writer of {@code replayRecords} (DOC-22 §4.4): the shared chunk writer with replay semantics, then the dead letters
 * of the replayed records are resolved in the same transaction. Keeps the event-time range and counters of the
 * replay in the step context for the request's stats.
 */
public class ReplayChunkWriter implements ItemWriter<WriteSet> {

    public static final String RESOLVED = "pti.replay.dlqResolved";
    public static final String MIN_EVENT_TS = "pti.replay.minEventTs";
    public static final String MAX_EVENT_TS = "pti.replay.maxEventTs";

    private final ChunkWriter writer;
    private final DeadLetterReplays deadLetters;
    private final BusinessClock clock;
    private final Supplier<StepExecution> step;

    public ReplayChunkWriter(ChunkWriter writer, DeadLetterReplays deadLetters, BusinessClock clock) {
        this(writer, deadLetters, clock, StepValues::current);
    }

    ReplayChunkWriter(
            ChunkWriter writer, DeadLetterReplays deadLetters, BusinessClock clock, Supplier<StepExecution> step) {
        this.writer = writer;
        this.deadLetters = deadLetters;
        this.clock = clock;
        this.step = step;
    }

    @Override
    public void write(Chunk<? extends WriteSet> chunk) {
        StepExecution execution = step.get();
        UUID batchId = StepValues.batchId(execution);
        WriteOutcome outcome = writer.write(
                List.copyOf(chunk.getItems()), new WriteContext(batchId, RunMode.BATCH, true, clock.instant()));
        StepValues.addRejected(execution, outcome.rejected());
        List<InboundMessage> written =
                outcome.written().stream().map(WriteSet::origin).toList();
        int resolved = deadLetters.resolve(written, batchId);
        ExecutionContext context = execution.getExecutionContext();
        context.putLong(RESOLVED, context.getLong(RESOLVED, 0L) + resolved);
        outcome.minEventTimestamp().ifPresent(t -> keep(context, MIN_EVENT_TS, t, true));
        outcome.maxEventTimestamp().ifPresent(t -> keep(context, MAX_EVENT_TS, t, false));
    }

    private static void keep(ExecutionContext context, String key, Instant value, boolean min) {
        String current = context.getString(key, "");
        if (current.isEmpty()) {
            context.putString(key, value.toString());
            return;
        }
        Instant existing = Instant.parse(current);
        if (min ? value.isBefore(existing) : value.isAfter(existing)) {
            context.putString(key, value.toString());
        }
    }
}
