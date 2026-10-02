package dev.pti.api.stream.application;

import dev.pti.api.stream.domain.HubEvent;
import dev.pti.api.stream.domain.Ulids;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The last minutes of {@code alerts}, {@code jobs} and {@code dlq} events in arrival order (DOC-26 §4.3), for the
 * replay of {@code Last-Event-ID}. Written by the consumer thread, read by threads that open connections.
 *
 * <p>{@link #coveredFrom()} is the earliest instant the buffer is known to hold everything from: the start of the
 * prefill, then moved forward by what ages out or overflows. A replay from before it would have a gap, so it is a
 * {@code resync} instead.
 */
public final class EventRingBuffer {

    private final Duration window;
    private final int maxEvents;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ArrayDeque<HubEvent> events = new ArrayDeque<>();
    private final Map<String, Long> seqById = new HashMap<>();
    private final Object readiness = new Object();
    private Instant coveredFrom = Instant.MAX;
    private boolean ready;

    public EventRingBuffer(Duration window, int maxEvents) {
        this.window = window;
        this.maxEvents = maxEvents;
    }

    /** Empties the buffer before a prefill that starts at {@code from} (DOC-26 §4.2). */
    public void reset(Instant from) {
        lock.writeLock().lock();
        try {
            events.clear();
            seqById.clear();
            coveredFrom = from;
        } finally {
            lock.writeLock().unlock();
        }
        synchronized (readiness) {
            ready = false;
        }
    }

    /**
     * Adds an event and drops what is older than the window or over the size limit.
     *
     * @return how many events were dropped because of the size limit
     */
    public int append(HubEvent event, Instant now) {
        lock.writeLock().lock();
        try {
            events.addLast(event);
            seqById.put(event.id(), event.seq());
            Instant oldest = now.minus(window);
            while (!events.isEmpty() && events.peekFirst().occurredAt().isBefore(oldest)) {
                evictFirst();
            }
            if (coveredFrom.isBefore(oldest)) {
                coveredFrom = oldest;
            }
            int overflow = 0;
            while (events.size() > maxEvents) {
                HubEvent evicted = evictFirst();
                overflow++;
                if (evicted.occurredAt().isAfter(coveredFrom)) {
                    coveredFrom = evicted.occurredAt();
                }
            }
            return overflow;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private HubEvent evictFirst() {
        HubEvent evicted = events.removeFirst();
        seqById.remove(evicted.id());
        return evicted;
    }

    /** The events after the one with this id, or empty if the id is not in the buffer. */
    public Optional<List<HubEvent>> after(String id) {
        lock.readLock().lock();
        try {
            Long seq = seqById.get(id);
            if (seq == null) {
                return Optional.empty();
            }
            List<HubEvent> after = new ArrayList<>();
            events.stream().filter(e -> e.seq() > seq).forEach(after::add);
            return Optional.of(after);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** The events whose ULID time is at or after {@code from}, or empty if the buffer does not reach that far back. */
    public Optional<List<HubEvent>> since(Instant from) {
        lock.readLock().lock();
        try {
            if (from.isBefore(coveredFrom)) {
                return Optional.empty();
            }
            List<HubEvent> since = new ArrayList<>();
            events.stream()
                    .filter(e -> !Ulids.time(e.id()).orElse(e.occurredAt()).isBefore(from))
                    .forEach(since::add);
            return Optional.of(since);
        } finally {
            lock.readLock().unlock();
        }
    }

    public int size() {
        lock.readLock().lock();
        try {
            return events.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public Instant coveredFrom() {
        lock.readLock().lock();
        try {
            return coveredFrom;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** The prefill has reached the end offsets it started from. */
    public void markReady() {
        synchronized (readiness) {
            ready = true;
            readiness.notifyAll();
        }
    }

    public boolean ready() {
        synchronized (readiness) {
            return ready;
        }
    }

    /** Waits until {@link #ready()}, at most {@code timeout}; returns whether it is. */
    public boolean awaitReady(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (readiness) {
            while (!ready) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                readiness.wait(Math.max(1, left / 1_000_000));
            }
            return true;
        }
    }
}
