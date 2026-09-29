package dev.pti.etl.batch;

import dev.pti.etl.fault.FaultInjector;
import dev.pti.etl.fault.FaultPoint;
import org.jspecify.annotations.Nullable;
import org.springframework.batch.core.listener.ChunkListener;
import org.springframework.batch.core.listener.ItemProcessListener;
import org.springframework.batch.core.listener.ItemWriteListener;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.infrastructure.item.Chunk;

/**
 * The fault points of DOC-19 §8 in a Spring Batch chunk step. {@code afterWrite} runs inside the chunk transaction,
 * before the commit; {@code AFTER_COMMIT_BEFORE_ACK} exists only in streaming.
 */
public class FaultStepListener
        implements ChunkListener<Object, Object>, ItemProcessListener<Object, Object>, ItemWriteListener<Object> {

    private final FaultInjector faults;

    public FaultStepListener(FaultInjector faults) {
        this.faults = faults;
    }

    /** The legacy step calls this variant, the new one {@code beforeChunk(Chunk)} (DR-80). */
    @Override
    @SuppressWarnings("removal")
    public void beforeChunk(ChunkContext context) {
        faults.hit(FaultPoint.BEFORE_READ);
    }

    @Override
    public void beforeProcess(Object item) {
        faults.hit(FaultPoint.BEFORE_PROCESS);
    }

    @Override
    public void afterProcess(Object item, @Nullable Object result) {
        faults.hit(FaultPoint.AFTER_PROCESS);
    }

    @Override
    public void beforeWrite(Chunk<? extends Object> items) {
        faults.hit(FaultPoint.BEFORE_WRITE);
    }

    @Override
    public void afterWrite(Chunk<? extends Object> items) {
        faults.hit(FaultPoint.AFTER_WRITE_BEFORE_COMMIT);
    }
}
