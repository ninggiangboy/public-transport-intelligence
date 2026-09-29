package dev.pti.etl.batch;

import dev.pti.common.dq.DlqStage;
import dev.pti.common.error.DataException;
import dev.pti.etl.core.InboundMessage;
import dev.pti.etl.core.MessageProcessors;
import dev.pti.etl.core.WriteSet;
import dev.pti.etl.write.DeadLetter;
import dev.pti.etl.write.DeadLetterWriter;
import dev.pti.etl.write.RunMode;
import dev.pti.etl.write.WriteStats;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.listener.SkipListener;
import org.springframework.batch.core.step.StepExecution;

/**
 * Writes skipped items to {@code ops.dead_letter} (DOC-19 §4.5). Spring Batch calls a skip listener inside the chunk
 * transaction before the commit, so a chunk that rolls back leaves no dead letter behind (test B-05).
 */
public class DeadLetterSkipListener implements SkipListener<InboundMessage, WriteSet> {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterSkipListener.class);

    private final DeadLetterWriter deadLetters;
    private final MessageProcessors processors;
    private final WriteStats stats;
    private final Supplier<StepExecution> step;

    public DeadLetterSkipListener(DeadLetterWriter deadLetters, MessageProcessors processors, WriteStats stats) {
        this(deadLetters, processors, stats, StepValues::current);
    }

    DeadLetterSkipListener(
            DeadLetterWriter deadLetters,
            MessageProcessors processors,
            WriteStats stats,
            Supplier<StepExecution> step) {
        this.deadLetters = deadLetters;
        this.processors = processors;
        this.stats = stats;
        this.step = step;
    }

    /** A reader that can name the bad record throws {@link UnreadableRecordException}; anything else is only logged. */
    @Override
    public void onSkipInRead(Throwable t) {
        if (t instanceof UnreadableRecordException unreadable) {
            write(DeadLetter.of(unreadable.message(), unreadable.error(), null, batchId()), unreadable.message());
        } else {
            log.warn("Skipped an unreadable item that names no record: {}", t.toString());
        }
    }

    @Override
    public void onSkipInProcess(InboundMessage item, Throwable t) {
        write(DeadLetter.of(item, asDataException(t), businessKey(item), batchId()), item);
    }

    @Override
    public void onSkipInWrite(WriteSet item, Throwable t) {
        write(DeadLetter.load(item.origin(), t, item.businessKey(), batchId()), item.origin());
    }

    private void write(DeadLetter letter, InboundMessage message) {
        StepExecution execution = step.get();
        DeadLetterWriter.DeadLetterResult result =
                StepValues.replay(execution) ? deadLetters.writeReplay(letter) : deadLetters.write(letter);
        if (result == DeadLetterWriter.DeadLetterResult.INSERTED) {
            StepValues.increment(execution, StepValues.DLQ_INSERTED);
        } else if (result == DeadLetterWriter.DeadLetterResult.UPDATED) {
            StepValues.increment(execution, StepValues.DLQ_UPDATED);
        }
        stats.recordSkipped(message.source(), RunMode.BATCH, 1);
    }

    private UUID batchId() {
        return StepValues.batchId(step.get());
    }

    private @Nullable String businessKey(InboundMessage item) {
        try {
            return processors.forSource(item.source()).businessKey(item);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The skip policy only skips data errors, so anything else here is a data error the processor did not wrap. */
    private static DataException asDataException(Throwable t) {
        return t instanceof DataException data
                ? data
                : new DataException(DlqStage.DESERIALIZE, null, String.valueOf(t.getMessage()), t);
    }
}
