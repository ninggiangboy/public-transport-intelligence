package dev.pti.simulator.emit;

import dev.pti.simulator.feed.Feed;
import dev.pti.simulator.feed.ServiceDateMapper;
import dev.pti.simulator.feed.ServiceDays;
import dev.pti.simulator.motion.DelayModel;
import dev.pti.simulator.motion.DelayParameters;
import dev.pti.simulator.motion.Fleet;
import dev.pti.simulator.motion.SegmentOverlay;
import dev.pti.simulator.rate.RateControl;
import dev.pti.testing.TestClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * An emitter on a feed with the documented defaults, a manual clock and a sink that keeps every message. Scenario
 * hooks can be plugged in the way the application wires them.
 */
public final class EmitterHarness {

    private final TestClock clock;
    private final RateControl rate;
    private final Emitter emitter;
    private final List<OutboundMessage> sent = new ArrayList<>();
    private final MessageSink sink = message -> sent.add(message);
    private final List<Runnable> afterTick = new ArrayList<>();

    public EmitterHarness(Feed feed, Instant start, long seed, double gtfsRt) {
        this(feed, start, seed, gtfsRt, SegmentOverlay.NONE, VehicleMarks.NONE, List::of);
    }

    /**
     * @param overlay the scenarios' segment overlays, e.g. {@code ScenarioHooks}
     * @param marks which runs affect a vehicle
     * @param interceptors the scenarios' interceptors, in order
     */
    public EmitterHarness(
            Feed feed,
            Instant start,
            long seed,
            double gtfsRt,
            SegmentOverlay overlay,
            VehicleMarks marks,
            Supplier<List<MessageInterceptor>> interceptors) {
        this.clock = TestClock.at(start);
        this.rate = new RateControl(gtfsRt, 1.0);
        ServiceDays days =
                new ServiceDays(feed, new ServiceDateMapper(feed.calendar(), "auto"), Duration.ofMinutes(10));
        DelayModel model = new DelayModel(seed, DelayParameters.defaults(), feed.zone(), overlay);
        Fleet fleet = new Fleet(days, model, Duration.ofMinutes(30), Duration.ofMinutes(30));
        MessageFactory messages = new MessageFactory(clock, seed, 5, 0.5, 10);
        this.emitter = new Emitter(
                clock,
                fleet,
                messages,
                new InterceptingSink(sink, interceptors, clock),
                rate,
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                marks);
    }

    /** Ticks every 200 ms of business time for {@code duration}. */
    public EmitterHarness run(Duration duration) {
        long end = clock.millis() + duration.toMillis();
        tick();
        while (clock.millis() < end) {
            clock.advance(Duration.ofMillis(200));
            tick();
        }
        return this;
    }

    private void tick() {
        emitter.tickUntil(clock.millis());
        afterTick.forEach(Runnable::run);
    }

    /** Runs {@code action} after every tick, e.g. draining a resend queue. */
    public EmitterHarness afterTick(Runnable action) {
        afterTick.add(action);
        return this;
    }

    public List<OutboundMessage> sent() {
        return sent;
    }

    /** The sink that keeps messages, past the interceptors. */
    public MessageSink sink() {
        return sink;
    }

    public RateControl rate() {
        return rate;
    }

    public Emitter emitter() {
        return emitter;
    }

    public TestClock clock() {
        return clock;
    }
}
