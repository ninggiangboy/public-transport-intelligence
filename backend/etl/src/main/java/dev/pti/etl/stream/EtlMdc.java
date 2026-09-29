package dev.pti.etl.stream;

import org.slf4j.MDC;

/** {@code batch_id}, {@code source} and {@code listener} in the log context of one poll (DOC-19 §10). */
public final class EtlMdc implements AutoCloseable {

    public static final String BATCH_ID = "batch_id";
    public static final String SOURCE = "source";
    public static final String LISTENER = "listener";

    private EtlMdc() {}

    public static EtlMdc open(StreamChunkRequest request) {
        MDC.put(BATCH_ID, request.batchId().toString());
        MDC.put(SOURCE, request.source().name());
        MDC.put(LISTENER, request.listenerId());
        return new EtlMdc();
    }

    @Override
    public void close() {
        MDC.remove(BATCH_ID);
        MDC.remove(SOURCE);
        MDC.remove(LISTENER);
    }
}
