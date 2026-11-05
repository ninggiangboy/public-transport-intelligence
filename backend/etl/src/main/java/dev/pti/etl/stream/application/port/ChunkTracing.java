package dev.pti.etl.stream.application.port;

import dev.pti.etl.stream.application.StreamChunkRequest;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The spans of one poll (DOC-28 §5.2). Implemented by {@code stream.adapter.out.tracing} with Micrometer; with tracing
 * off everything here is a no-op and {@link #currentSpan()} returns {@code null}.
 */
public interface ChunkTracing {

    /** Starts {@code pti.etl.poll} and puts it in scope; closing the result ends it. */
    Poll poll(StreamChunkRequest request);

    /**
     * Registers the {@code pti.etl.commit} span on the current transaction, so it covers from just before the commit
     * until it has completed. Does nothing when there is no transaction in progress.
     */
    void commitSpan();

    /** Runs {@code work} in a child span of the current one. */
    <T> T child(String name, String source, Supplier<T> work);

    void child(String name, String source, Runnable work);

    /** The trace of the span in scope, for work that continues on another thread; {@code null} without one. */
    @Nullable
    PollSpan currentSpan();

    /** The poll span and its scope. */
    interface Poll extends AutoCloseable {

        void outcome(String outcome);

        void error(Throwable error);

        @Override
        void close();
    }
}
