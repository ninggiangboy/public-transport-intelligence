package dev.pti.simulator.emit;

import dev.pti.simulator.feed.Feed;
import dev.pti.simulator.feed.ServiceDateMapper;
import dev.pti.simulator.feed.ServiceDays;
import dev.pti.simulator.motion.DelayModel;
import dev.pti.simulator.motion.DelayParameters;
import dev.pti.simulator.motion.Fleet;
import dev.pti.simulator.rate.RateControl;
import dev.pti.testing.TestClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** An emitter on a feed with the documented defaults, a manual clock and a sink that keeps every message. */
public final class EmitterHarness {

    private final TestClock clock;
    private final RateControl rate;
    private final Emitter emitter;
    private final List<OutboundMessage> sent = new ArrayList<>();

    public EmitterHarness(Feed feed, Instant start, long seed, double gtfsRt) {
        this.clock = TestClock.at(start);
        this.rate = new RateControl(gtfsRt, 1.0);
        ServiceDays days =
                new ServiceDays(feed, new ServiceDateMapper(feed.calendar(), "auto"), Duration.ofMinutes(10));
        DelayModel model = new DelayModel(seed, DelayParameters.defaults(), feed.zone());
        Fleet fleet = new Fleet(days, model, Duration.ofMinutes(30), Duration.ofMinutes(30));
        MessageFactory messages = new MessageFactory(clock, seed, 5, 0.5, 10);
        this.emitter = new Emitter(
                clock,
                fleet,
                messages,
                message -> sent.add(message),
                rate,
                Duration.ofSeconds(5),
                Duration.ofSeconds(30));
    }

    /** Ticks every 200 ms of business time for {@code duration}. */
    public EmitterHarness run(Duration duration) {
        long end = clock.millis() + duration.toMillis();
        emitter.tickUntil(clock.millis());
        while (clock.millis() < end) {
            clock.advance(Duration.ofMillis(200));
            emitter.tickUntil(clock.millis());
        }
        return this;
    }

    public List<OutboundMessage> sent() {
        return sent;
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
