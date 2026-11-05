package dev.pti.etl.stream.adapter.out.tracing;

import dev.pti.etl.stream.application.StreamChunkRequest;
import dev.pti.etl.stream.application.port.ChunkTracing;
import dev.pti.etl.stream.application.port.PollSpan;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** Tracing off (DOC-28 §5.2): every span is a no-op. Used by the unit and integration tests of the template. */
public final class NoopChunkTracing implements ChunkTracing {

    public static final NoopChunkTracing INSTANCE = new NoopChunkTracing();

    private static final Poll NOOP_POLL = new Poll() {
        @Override
        public void outcome(String outcome) {}

        @Override
        public void error(Throwable error) {}

        @Override
        public void close() {}
    };

    private NoopChunkTracing() {}

    @Override
    public Poll poll(StreamChunkRequest request) {
        return NOOP_POLL;
    }

    @Override
    public void commitSpan() {}

    @Override
    public <T> T child(String name, String source, Supplier<T> work) {
        return work.get();
    }

    @Override
    public void child(String name, String source, Runnable work) {
        work.run();
    }

    @Override
    public @Nullable PollSpan currentSpan() {
        return null;
    }
}
