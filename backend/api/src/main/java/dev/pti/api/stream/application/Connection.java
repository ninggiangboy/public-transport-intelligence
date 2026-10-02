package dev.pti.api.stream.application;

import dev.pti.api.stream.application.port.FrameSink;
import dev.pti.api.stream.domain.Frame;
import dev.pti.api.stream.domain.ResyncReason;
import dev.pti.api.stream.domain.Subscription;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;
import org.jspecify.annotations.Nullable;

/**
 * One open {@code /stream} response (DOC-26 §6.2): a bounded queue that the hub offers frames to without blocking,
 * and a sink that one writer thread drains it into. While the connection opens, live frames wait in a list, so that
 * the replay of {@code Last-Event-ID} goes out first and an event both replayed and live goes out once.
 */
public final class Connection {

    private final long id;
    private final Subscription subscription;
    private final FrameSink sink;
    private final ArrayBlockingQueue<Frame> queue;
    private final IntConsumer onSlowClient;
    private final AtomicBoolean closed = new AtomicBoolean();
    private @Nullable List<Frame> opening = new ArrayList<>();
    private volatile long sendingSinceNanos;

    /**
     * @param onSlowClient told how many frames were dropped when the queue was full
     */
    Connection(long id, Subscription subscription, FrameSink sink, int capacity, IntConsumer onSlowClient) {
        this.id = id;
        this.subscription = subscription;
        this.sink = sink;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.onSlowClient = onSlowClient;
    }

    public long id() {
        return id;
    }

    public Subscription subscription() {
        return subscription;
    }

    public boolean isClosed() {
        return closed.get();
    }

    /** From the hub: queues a frame, or replaces the whole queue by a {@code resync} when it is full. */
    synchronized void deliver(Frame frame, Instant now) {
        if (closed.get()) {
            return;
        }
        if (opening != null) {
            opening.add(frame);
            return;
        }
        enqueue(frame, now);
    }

    /** Ends the opening: the given frames first, then the live ones that came meanwhile, minus duplicates. */
    synchronized void open(List<Frame> first, Instant now) {
        List<Frame> live = opening == null ? List.of() : opening;
        opening = null;
        Set<Long> liveSeqs = new HashSet<>();
        live.forEach(frame -> {
            if (frame instanceof Frame.Event event) {
                liveSeqs.add(event.event().seq());
            }
        });
        for (Frame frame : first) {
            if (frame instanceof Frame.Event event
                    && liveSeqs.contains(event.event().seq())) {
                continue;
            }
            enqueue(frame, now);
        }
        live.forEach(frame -> enqueue(frame, now));
    }

    private void enqueue(Frame frame, Instant now) {
        if (queue.offer(frame)) {
            return;
        }
        int dropped = queue.size() + 1;
        queue.clear();
        // Room for one after clear(): only this thread adds, under the connection's lock.
        queue.add(new Frame.Resync(ResyncReason.SLOW_CLIENT, subscription.channels(), now));
        onSlowClient.accept(dropped);
    }

    /** The next frame, waiting at most {@code millis}; {@code null} if none came. */
    @Nullable
    Frame next(long millis) throws InterruptedException {
        return queue.poll(millis, TimeUnit.MILLISECONDS);
    }

    void send(Frame frame) throws java.io.IOException {
        sendingSinceNanos = System.nanoTime();
        try {
            sink.send(frame);
        } finally {
            sendingSinceNanos = 0;
        }
    }

    /** How long the current write has been blocked, in nanoseconds; 0 when the writer is not writing. */
    long writingForNanos(long nowNanos) {
        long since = sendingSinceNanos;
        return since == 0 ? 0 : nowNanos - since;
    }

    /** Marks the connection closed and ends the response; true only for the first call. */
    boolean close() {
        if (!closed.compareAndSet(false, true)) {
            return false;
        }
        queue.clear();
        sink.complete();
        return true;
    }

    int queued() {
        return queue.size();
    }
}
