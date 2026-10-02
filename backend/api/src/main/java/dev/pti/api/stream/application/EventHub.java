package dev.pti.api.stream.application;

import dev.pti.api.platform.domain.ServiceUnavailableException;
import dev.pti.api.stream.application.port.FrameSink;
import dev.pti.api.stream.application.port.StreamMetrics;
import dev.pti.api.stream.domain.CloseReason;
import dev.pti.api.stream.domain.Frame;
import dev.pti.api.stream.domain.HubEvent;
import dev.pti.api.stream.domain.ReplayKind;
import dev.pti.api.stream.domain.ResyncReason;
import dev.pti.api.stream.domain.SseProjection;
import dev.pti.api.stream.domain.Subscription;
import dev.pti.api.stream.domain.TooManyStreamsException;
import dev.pti.api.stream.domain.Ulids;
import dev.pti.common.events.UiChannel;
import dev.pti.common.time.BusinessClock;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The SSE hub of one pod (DOC-26 §2–§7): it numbers the events the consumer hands it, keeps the replayable ones in
 * the ring buffer, throttles vehicle positions, and offers every event to the connections that want it. Each
 * connection has its own queue and writer thread, so a slow client only ever slows itself.
 */
public final class EventHub {

    private static final Logger log = LoggerFactory.getLogger(EventHub.class);

    /** The {@code retry:} of the first frame (DOC-33 §2.2). */
    static final long RETRY_MILLIS = 1000;

    private static final long WRITER_POLL_MILLIS = 1000;

    private final EventRingBuffer buffer;
    private final VehicleThrottle throttle;
    private final StreamMetrics metrics;
    private final BusinessClock clock;
    private final StreamSettings settings;
    private final AtomicLong seq = new AtomicLong();
    private final AtomicLong connectionIds = new AtomicLong();
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, AtomicInteger> perClient = new ConcurrentHashMap<>();
    private final Set<String> warnedTypes = ConcurrentHashMap.newKeySet();
    private volatile boolean accepting = true;
    private volatile @Nullable Instant lastReceivedAt;

    public EventHub(
            EventRingBuffer buffer,
            VehicleThrottle throttle,
            StreamMetrics metrics,
            BusinessClock clock,
            StreamSettings settings) {
        this.buffer = buffer;
        this.throttle = throttle;
        this.metrics = metrics;
        this.clock = clock;
        this.settings = settings;
    }

    // ------------------------------------------------------------------------------------------ consumer side

    /**
     * An event from the topic. Prefill events ({@code live = false}) only go to the buffer; live ones also go to the
     * connections (DOC-26 §4.2).
     */
    public void accept(HubEvent received, boolean live) {
        HubEvent event = received.withSeq(seq.incrementAndGet());
        Instant now = clock.realNow();
        lastReceivedAt = event.occurredAt();
        if (event.replayed()) {
            int overflow = buffer.append(event, now);
            if (overflow > 0) {
                metrics.dropped("buffer_overflow", overflow);
            }
        }
        if (!live) {
            return;
        }
        if (event.channel() == UiChannel.VEHICLES) {
            HubEvent throttled = throttle.offer(event, now);
            if (throttled == null) {
                return;
            }
            event = throttled;
        }
        broadcast(event, now);
    }

    /** The merged vehicle positions whose second has passed (DOC-26 §6.3). */
    public void flushVehicles() {
        Instant now = clock.realNow();
        for (HubEvent event : throttle.due(now)) {
            broadcast(event, now);
        }
    }

    /** Before a prefill: empties the buffer, from the start of the window. */
    public void startPrefill(Instant from) {
        buffer.reset(from);
    }

    public void prefillDone() {
        buffer.markReady();
    }

    /**
     * The consumer lost its place (partitions assigned again): the buffer may have a gap, so every connection is told
     * to refetch (DOC-26 §4.2, {@code CONSUMER_RESET}).
     */
    public void consumerReset() {
        Instant now = clock.realNow();
        log.info("SSE consumer reassigned; {} connections get resync CONSUMER_RESET", connections.size());
        for (Connection connection : connections) {
            connection.deliver(
                    new Frame.Resync(
                            ResyncReason.CONSUMER_RESET,
                            connection.subscription().channels(),
                            now),
                    now);
        }
    }

    private void broadcast(HubEvent event, Instant now) {
        Duration sincePublish = Duration.between(event.occurredAt(), now);
        metrics.publishToEmit(event.channel(), sincePublish.isNegative() ? Duration.ZERO : sincePublish);
        Instant source = event.sourceRecordTs();
        if (source != null) {
            Duration endToEnd = Duration.between(source, now);
            metrics.endToEnd(event.channel(), endToEnd.isNegative() ? Duration.ZERO : endToEnd);
        }
        for (Connection connection : connections) {
            Frame frame = frameFor(connection.subscription(), event, true);
            if (frame != null) {
                connection.deliver(frame, now);
            }
        }
    }

    /** The frame of an event for a subscription, or {@code null} when it does not get the event (DOC-33 §4). */
    private @Nullable Frame frameFor(Subscription subscription, HubEvent event, boolean live) {
        if (!subscription.wants(event)) {
            return null;
        }
        boolean anonymous = !subscription.authenticated();
        if (anonymous && !SseProjection.hasPublicView(event.type())) {
            if (warnedTypes.add(event.type())) {
                log.warn(
                        "UI event type {} has audience PUBLIC but no public projection; not sent to anonymous"
                                + " clients",
                        event.type());
            }
            return null;
        }
        return new Frame.Event(event, anonymous, live);
    }

    // ------------------------------------------------------------------------------------------ connections

    /**
     * Opens a connection: checks the caps, registers it so that live events start queuing, then works out the replay
     * of {@code Last-Event-ID} (DOC-26 §5) and starts its writer.
     *
     * @throws ServiceUnavailableException when the pod is full or shutting down
     * @throws TooManyStreamsException when the caller holds as many streams as it may
     */
    public Connection open(Subscription subscription, @Nullable String lastEventId, FrameSink sink)
            throws InterruptedException {
        if (!accepting) {
            throw new ServiceUnavailableException("The server is shutting down; connect again.", 1);
        }
        if (connections.size() >= settings.maxConnections()) {
            throw new ServiceUnavailableException("Too many event streams on this server.", 5);
        }
        reserve(subscription);
        Instant now = clock.realNow();
        Connection connection = new Connection(
                connectionIds.incrementAndGet(),
                subscription,
                sink,
                settings.connectionQueue(),
                dropped -> metrics.dropped("slow_client", dropped));
        connections.add(connection);
        List<Frame> first = new ArrayList<>();
        first.add(new Frame.Retry(RETRY_MILLIS));
        ReplayKind kind;
        try {
            kind = replay(subscription, lastEventId, first, now);
        } catch (InterruptedException | RuntimeException e) {
            close(connection, CloseReason.SHUTDOWN);
            throw e;
        }
        metrics.opened(kind);
        connection.open(first, now);
        Thread.ofVirtual().name("sse-writer-" + connection.id()).start(() -> write(connection));
        return connection;
    }

    private void reserve(Subscription subscription) {
        if (!settings.capsEnabled()) {
            return;
        }
        boolean user = subscription.clientKey().startsWith("user:");
        int limit = user ? settings.perUser() : settings.perIp();
        AtomicInteger count = perClient.computeIfAbsent(subscription.clientKey(), key -> new AtomicInteger());
        if (count.incrementAndGet() > limit) {
            count.decrementAndGet();
            throw new TooManyStreamsException(limit);
        }
    }

    private void release(Subscription subscription) {
        if (!settings.capsEnabled()) {
            return;
        }
        perClient.computeIfPresent(
                subscription.clientKey(), (key, count) -> count.decrementAndGet() <= 0 ? null : count);
    }

    /** Adds the replay of {@code Last-Event-ID} to {@code first}: exact id, then ULID time, else a resync. */
    private ReplayKind replay(Subscription subscription, @Nullable String lastEventId, List<Frame> first, Instant now)
            throws InterruptedException {
        if (lastEventId == null || lastEventId.isBlank()) {
            return ReplayKind.NONE;
        }
        Set<UiChannel> replayed = subscription.replayedChannels();
        boolean ready = buffer.awaitReady(settings.readyWait());
        ReplayKind kind = ReplayKind.EXACT;
        Optional<List<HubEvent>> events = ready ? buffer.after(lastEventId) : Optional.empty();
        if (ready && events.isEmpty()) {
            kind = ReplayKind.TIME;
            events = Ulids.time(lastEventId).flatMap(time -> buffer.since(time.minus(settings.replayClockSkew())));
        }
        if (events.isEmpty()) {
            if (replayed.isEmpty()) {
                return ReplayKind.NONE;
            }
            first.add(new Frame.Resync(ResyncReason.BUFFER_EXPIRED, replayed, now));
            return ReplayKind.RESYNC;
        }
        for (HubEvent event : events.get()) {
            Frame frame = frameFor(subscription, event, false);
            if (frame != null) {
                first.add(frame);
            }
        }
        return kind;
    }

    private void write(Connection connection) {
        try {
            while (!connection.isClosed()) {
                Frame frame = connection.next(WRITER_POLL_MILLIS);
                if (frame == null) {
                    continue;
                }
                connection.send(frame);
                if (frame instanceof Frame.Event event) {
                    metrics.emitted(event.event().channel(), event.event().type());
                }
            }
        } catch (IOException e) {
            close(connection, CloseReason.CLIENT_GONE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close(connection, CloseReason.SHUTDOWN);
        } catch (RuntimeException e) {
            log.debug("SSE writer of connection {} failed", connection.id(), e);
            close(connection, CloseReason.CLIENT_GONE);
        }
    }

    public void close(Connection connection, CloseReason reason) {
        if (connection.close()) {
            connections.remove(connection);
            release(connection.subscription());
            metrics.closed(reason);
            log.debug("SSE connection {} closed: {}", connection.id(), reason);
        }
    }

    /** {@code heartbeat} to every connection (DOC-33 §5.10). */
    public void heartbeat() {
        Instant now = clock.realNow();
        Frame heartbeat = new Frame.Heartbeat(now, clock.instant());
        for (Connection connection : connections) {
            connection.deliver(heartbeat, now);
        }
    }

    /** Closes the connections whose token has expired or whose write is stuck (DOC-26 §6.2, §7). */
    public void watchdog() {
        Instant now = clock.realNow();
        long nowNanos = System.nanoTime();
        long stall = settings.writeStallTimeout().toNanos();
        for (Connection connection : connections) {
            Instant expires = connection.subscription().tokenExpiresAt();
            if (expires != null && !now.isBefore(expires)) {
                close(connection, CloseReason.TOKEN_EXPIRED);
            } else if (connection.writingForNanos(nowNanos) > stall) {
                log.info(
                        "SSE connection {} of {} closed: write stalled, {} frames queued",
                        connection.id(),
                        connection.subscription().clientKey(),
                        connection.queued());
                close(connection, CloseReason.WRITE_STALLED);
            }
        }
    }

    /** Stops taking connections and ends the open ones (DOC-26 §7.1). */
    public void shutdown() {
        accepting = false;
        for (Connection connection : connections) {
            close(connection, CloseReason.SHUTDOWN);
        }
    }

    public boolean accepting() {
        return accepting;
    }

    public int connectionCount() {
        return connections.size();
    }

    /** Open connections that have the channel, anonymous or not, for {@code pti_api_sse_connections}. */
    public int connectionCount(UiChannel channel, boolean authenticated) {
        return (int) connections.stream()
                .filter(c -> c.subscription().authenticated() == authenticated)
                .filter(c -> c.subscription().channels().contains(channel))
                .count();
    }

    public int bufferSize() {
        return buffer.size();
    }

    /** {@code now − occurredAt} of the last event the consumer handed over, or zero before the first. */
    public Duration consumerLag() {
        Instant last = lastReceivedAt;
        if (last == null) {
            return Duration.ZERO;
        }
        Duration lag = Duration.between(last, clock.realNow());
        return lag.isNegative() ? Duration.ZERO : lag;
    }
}
