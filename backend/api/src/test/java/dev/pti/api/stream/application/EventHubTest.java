package dev.pti.api.stream.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.pti.api.platform.domain.ServiceUnavailableException;
import dev.pti.api.stream.application.port.FrameSink;
import dev.pti.api.stream.application.port.StreamMetrics;
import dev.pti.api.stream.domain.CloseReason;
import dev.pti.api.stream.domain.Frame;
import dev.pti.api.stream.domain.HubEvent;
import dev.pti.api.stream.domain.ReplayKind;
import dev.pti.api.stream.domain.ResyncReason;
import dev.pti.api.stream.domain.Subscription;
import dev.pti.api.stream.domain.TooManyStreamsException;
import dev.pti.api.testing.StreamFixtures;
import dev.pti.common.events.Audience;
import dev.pti.common.events.UiChannel;
import dev.pti.testing.TestClock;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The hub of DOC-26 §5–§7 with an in-memory sink: replay, filtering, slow clients, caps, watchdog. */
class EventHubTest {

    private final TestClock clock = TestClock.atDefault();
    private final RecordingMetrics metrics = new RecordingMetrics();
    private final EventHub hub = hub(1000, 5);

    @AfterEach
    void shutDown() {
        hub.shutdown();
    }

    private EventHub hub(int queue, int perIp) {
        StreamSettings settings = new StreamSettings(
                Duration.ofMinutes(5),
                10_000,
                queue,
                Duration.ofMillis(300),
                1,
                3,
                Duration.ofSeconds(5),
                Duration.ofMillis(50),
                20,
                true,
                perIp,
                10);
        EventHub created = new EventHub(
                new EventRingBuffer(settings.bufferWindow(), settings.bufferMaxEvents()),
                new VehicleThrottle(1),
                metrics,
                clock,
                settings);
        created.startPrefill(clock.realNow().minus(Duration.ofMinutes(5)));
        created.prefillDone();
        return created;
    }

    private static Subscription anonymous(UiChannel... channels) {
        return new Subscription(EnumSet.of(channels[0], channels), Set.of(), false, null, "ip:10.0.0.1");
    }

    private static Subscription viewer(UiChannel... channels) {
        return new Subscription(EnumSet.of(channels[0], channels), Set.of(), true, null, "user:viewer");
    }

    private HubEvent alert(Audience audience) {
        clock.advance(Duration.ofMillis(10));
        return StreamFixtures.alert(clock.realNow(), audience, "18");
    }

    private static List<String> ids(RecordingSink sink) {
        return sink.frames.stream()
                .filter(Frame.Event.class::isInstance)
                .map(f -> ((Frame.Event) f).event().id())
                .toList();
    }

    @Test
    @DisplayName("A connection without Last-Event-ID gets retry, then the live events it may see, in order")
    void liveEvents() throws Exception {
        RecordingSink sink = new RecordingSink();
        hub.open(anonymous(UiChannel.ALERTS), null, sink);
        HubEvent first = alert(Audience.PUBLIC);
        HubEvent hidden = alert(Audience.OPERATIONS);
        HubEvent second = alert(Audience.PUBLIC);

        hub.accept(first, true);
        hub.accept(hidden, true);
        hub.accept(second, true);

        await().until(() -> ids(sink).size() == 2);
        assertThat(sink.frames.getFirst()).isInstanceOf(Frame.Retry.class);
        assertThat(ids(sink)).containsExactly(first.id(), second.id());
        assertThat(metrics.opened).containsExactly(ReplayKind.NONE);
        assertThat(metrics.publishToEmit).isEqualTo(3);
    }

    @Test
    @DisplayName("RT-02 Last-Event-ID in the buffer replays exactly what came after it")
    void exactReplay() throws Exception {
        HubEvent seen = alert(Audience.PUBLIC);
        HubEvent missed1 = alert(Audience.PUBLIC);
        HubEvent missed2 = alert(Audience.PUBLIC);
        hub.accept(seen, true);
        hub.accept(missed1, true);
        hub.accept(missed2, true);
        RecordingSink sink = new RecordingSink();

        hub.open(viewer(UiChannel.ALERTS), seen.id(), sink);

        await().until(() -> ids(sink).size() == 2);
        assertThat(ids(sink)).containsExactly(missed1.id(), missed2.id());
        assertThat(sink.frames.stream().filter(Frame.Event.class::isInstance).map(f -> ((Frame.Event) f).live()))
                .as("replayed frames are not measured")
                .containsOnly(false);
        assertThat(metrics.opened).containsExactly(ReplayKind.EXACT);
    }

    @Test
    @DisplayName("RT-04 an id that is not in the buffer (a vehicles.batch) replays by ULID time, 5 s back")
    void replayByTime() throws Exception {
        HubEvent before = alert(Audience.PUBLIC);
        clock.advance(Duration.ofSeconds(10));
        HubEvent nearly = alert(Audience.PUBLIC);
        clock.advance(Duration.ofSeconds(3));
        String vehiclesId = StreamFixtures.ulid(clock.realNow());
        HubEvent after = alert(Audience.PUBLIC);
        List.of(before, nearly, after).forEach(e -> hub.accept(e, true));
        RecordingSink sink = new RecordingSink();

        hub.open(viewer(UiChannel.ALERTS, UiChannel.VEHICLES), vehiclesId, sink);

        await().until(() -> ids(sink).size() == 2);
        assertThat(ids(sink)).containsExactly(nearly.id(), after.id());
        assertThat(metrics.opened).containsExactly(ReplayKind.TIME);
    }

    @Test
    @DisplayName("RT-03 an id older than the buffer gets resync BUFFER_EXPIRED for the replayed channels")
    void expired() throws Exception {
        String old = StreamFixtures.ulid(clock.realNow().minus(Duration.ofMinutes(6)));
        RecordingSink sink = new RecordingSink();

        hub.open(viewer(UiChannel.ALERTS, UiChannel.VEHICLES, UiChannel.JOBS), old, sink);

        await().until(() -> sink.frames.size() == 2);
        Frame.Resync resync = (Frame.Resync) sink.frames.get(1);
        assertThat(resync.reason()).isEqualTo(ResyncReason.BUFFER_EXPIRED);
        assertThat(resync.channels()).containsExactlyInAnyOrder(UiChannel.ALERTS, UiChannel.JOBS);
        assertThat(metrics.opened).containsExactly(ReplayKind.RESYNC);
    }

    @Test
    @DisplayName("Anonymous connections never get OPERATIONS or ENGINEERING events, nor a type without public view")
    void audience() throws Exception {
        RecordingSink anon = new RecordingSink();
        RecordingSink signedIn = new RecordingSink();
        hub.open(anonymous(UiChannel.ALERTS, UiChannel.JOBS), null, anon);
        hub.open(viewer(UiChannel.ALERTS, UiChannel.JOBS), null, signedIn);
        HubEvent bunching = HubEvent.received(
                StreamFixtures.ulid(clock.realNow()),
                "bunching.opened",
                UiChannel.ALERTS,
                Audience.PUBLIC,
                clock.realNow(),
                null,
                "18",
                java.util.Map.of("id", "b"));

        hub.accept(StreamFixtures.job(clock.realNow()), true);
        hub.accept(bunching, true);
        hub.accept(alert(Audience.PUBLIC), true);

        await().until(() -> ids(signedIn).size() == 3);
        await().until(() -> ids(anon).size() == 1);
        assertThat(((Frame.Event) anon.frames.get(1)).anonymous()).isTrue();
    }

    @Test
    @DisplayName("A route filter drops events of other routes but keeps those without a route")
    void routeFilter() throws Exception {
        RecordingSink sink = new RecordingSink();
        hub.open(new Subscription(EnumSet.of(UiChannel.ALERTS), Set.of("22"), true, null, "user:viewer"), null, sink);
        clock.advance(Duration.ofMillis(10));
        HubEvent noRoute = StreamFixtures.alert(clock.realNow(), Audience.PUBLIC, null);
        hub.accept(alert(Audience.PUBLIC), true);
        hub.accept(noRoute, true);

        await().until(() -> ids(sink).size() == 1);
        assertThat(ids(sink)).containsExactly(noRoute.id());
    }

    @Test
    @DisplayName(
            "RT-07 a client that does not read gets its queue replaced by resync SLOW_CLIENT; others are unaffected")
    void slowClient() throws Exception {
        EventHub small = hub(5, 5);
        try {
            BlockingSink stuck = new BlockingSink();
            RecordingSink fast = new RecordingSink();
            small.open(viewer(UiChannel.ALERTS), null, stuck);
            small.open(viewer(UiChannel.ALERTS), null, fast);
            stuck.entered.await();
            for (int i = 0; i < 20; i++) {
                small.accept(alert(Audience.PUBLIC), true);
                int sent = i + 1;
                await().until(() -> ids(fast).size() == sent);
            }

            assertThat(metrics.dropped).isGreaterThan(0);
            stuck.release.countDown();
            await().until(() -> stuck.frames.stream().anyMatch(Frame.Resync.class::isInstance));
            assertThat(stuck.frames.stream()
                            .filter(Frame.Resync.class::isInstance)
                            .map(f -> ((Frame.Resync) f).reason()))
                    .containsOnly(ResyncReason.SLOW_CLIENT);
        } finally {
            small.shutdown();
        }
    }

    @Test
    @DisplayName("A write blocked longer than the stall timeout closes the connection (WRITE_STALLED)")
    void writeStall() throws Exception {
        BlockingSink stuck = new BlockingSink();
        hub.open(viewer(UiChannel.ALERTS), null, stuck);
        stuck.entered.await();

        await().pollDelay(Duration.ofMillis(350)).until(() -> {
            hub.watchdog();
            return metrics.closed.contains(CloseReason.WRITE_STALLED);
        });
        assertThat(stuck.completed).isTrue();
        stuck.release.countDown();
    }

    @Test
    @DisplayName("RT-11 a connection whose token expires is closed (TOKEN_EXPIRED)")
    void tokenExpiry() throws Exception {
        RecordingSink sink = new RecordingSink();
        Instant exp = clock.realNow().plusSeconds(30);
        hub.open(new Subscription(EnumSet.of(UiChannel.ALERTS), Set.of(), true, exp, "user:viewer"), null, sink);

        hub.watchdog();
        assertThat(hub.connectionCount()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(30));
        hub.watchdog();

        assertThat(hub.connectionCount()).isZero();
        assertThat(metrics.closed).containsExactly(CloseReason.TOKEN_EXPIRED);
        assertThat(sink.completed).isTrue();
    }

    @Test
    @DisplayName("RT-10 the sixth stream of an anonymous IP is refused; closing one frees its place")
    void perClientCap() throws Exception {
        EventHub capped = hub(1000, 2);
        try {
            capped.open(anonymous(UiChannel.ALERTS), null, new RecordingSink());
            var second = capped.open(anonymous(UiChannel.ALERTS), null, new RecordingSink());

            assertThatThrownBy(() -> capped.open(anonymous(UiChannel.ALERTS), null, new RecordingSink()))
                    .isInstanceOf(TooManyStreamsException.class);
            capped.close(second, CloseReason.CLIENT_GONE);
            capped.open(anonymous(UiChannel.ALERTS), null, new RecordingSink());
        } finally {
            capped.shutdown();
        }
    }

    @Test
    @DisplayName("The pod cap and shutdown answer 503 before anything is registered")
    void podCapAndShutdown() throws Exception {
        hub.open(viewer(UiChannel.ALERTS), null, new RecordingSink());
        hub.open(viewer(UiChannel.ALERTS), null, new RecordingSink());
        hub.open(viewer(UiChannel.ALERTS), null, new RecordingSink());
        assertThatThrownBy(() -> hub.open(viewer(UiChannel.ALERTS), null, new RecordingSink()))
                .isInstanceOf(ServiceUnavailableException.class);

        hub.shutdown();

        assertThat(hub.connectionCount()).isZero();
        assertThat(metrics.closed).containsOnly(CloseReason.SHUTDOWN).hasSize(3);
        assertThatThrownBy(() -> hub.open(viewer(UiChannel.ALERTS), null, new RecordingSink()))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    @DisplayName("Heartbeat reaches every connection; a consumer reset sends resync CONSUMER_RESET")
    void heartbeatAndReset() throws Exception {
        RecordingSink sink = new RecordingSink();
        hub.open(anonymous(UiChannel.VEHICLES), null, sink);

        hub.heartbeat();
        hub.consumerReset();

        await().until(() -> sink.frames.size() == 3);
        assertThat(sink.frames.get(1)).isInstanceOf(Frame.Heartbeat.class);
        assertThat(((Frame.Resync) sink.frames.get(2)).reason()).isEqualTo(ResyncReason.CONSUMER_RESET);
    }

    @Test
    @DisplayName("A client gone (IOException) is closed as CLIENT_GONE")
    void clientGone() throws Exception {
        hub.open(viewer(UiChannel.ALERTS), null, new FrameSink() {
            @Override
            public void send(Frame frame) throws IOException {
                throw new IOException("broken pipe");
            }

            @Override
            public void complete() {}
        });

        await().until(() -> metrics.closed.contains(CloseReason.CLIENT_GONE));
        assertThat(hub.connectionCount()).isZero();
    }

    // ------------------------------------------------------------------------------------------ fakes

    static class RecordingSink implements FrameSink {
        final List<Frame> frames = new CopyOnWriteArrayList<>();
        volatile boolean completed;

        @Override
        public void send(Frame frame) throws IOException {
            frames.add(frame);
        }

        @Override
        public void complete() {
            completed = true;
        }
    }

    /** Blocks in its first send until released, as a client whose TCP window is full. */
    static final class BlockingSink extends RecordingSink {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void send(Frame frame) throws IOException {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            super.send(frame);
        }
    }

    static final class RecordingMetrics implements StreamMetrics {
        final List<ReplayKind> opened = Collections.synchronizedList(new ArrayList<>());
        final List<CloseReason> closed = Collections.synchronizedList(new ArrayList<>());
        volatile int dropped;
        volatile int publishToEmit;

        @Override
        public void opened(ReplayKind replay) {
            opened.add(replay);
        }

        @Override
        public void closed(CloseReason reason) {
            closed.add(reason);
        }

        @Override
        public void emitted(UiChannel channel, String type) {}

        @Override
        public synchronized void dropped(String reason, int frames) {
            dropped += frames;
        }

        @Override
        public synchronized void publishToEmit(UiChannel channel, Duration latency) {
            publishToEmit++;
        }

        @Override
        public void endToEnd(UiChannel channel, Duration latency) {}

        @Override
        public void invalidEvent() {}
    }
}
