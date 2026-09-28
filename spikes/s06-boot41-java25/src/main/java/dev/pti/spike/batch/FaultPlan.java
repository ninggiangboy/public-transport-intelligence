package dev.pti.spike.batch;

import java.util.concurrent.atomic.AtomicInteger;

/** One-shot faults injected by the writer. */
public final class FaultPlan {

    /** Thrown to simulate a process that dies mid-transaction; an Error is not caught by the chunk loop. */
    public static final class SimulatedCrash extends Error {
        public SimulatedCrash(String message) {
            super(message);
        }
    }

    private final AtomicInteger fatalAtId = new AtomicInteger(-1);
    private final AtomicInteger crashAtIdInScan = new AtomicInteger(-1);
    private final AtomicInteger transientAtId = new AtomicInteger(-1);

    public void transientOnceAt(int id) {
        transientAtId.set(id);
    }

    public void fatalOnceAt(int id) {
        fatalAtId.set(id);
    }

    public void crashOnceDuringScanAt(int id) {
        crashAtIdInScan.set(id);
    }

    void beforeWrite(int id, boolean singleItemChunk) {
        if (fatalAtId.compareAndSet(id, -1)) {
            throw new IllegalStateException("Injected fatal error at item " + id);
        }
        if (transientAtId.compareAndSet(id, -1)) {
            throw new org.springframework.dao.TransientDataAccessResourceException(
                    "Injected transient error at item " + id);
        }
        if (singleItemChunk && crashAtIdInScan.compareAndSet(id, -1)) {
            throw new SimulatedCrash("Injected crash during scan at item " + id);
        }
    }
}
