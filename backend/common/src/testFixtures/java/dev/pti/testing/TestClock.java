package dev.pti.testing;

import dev.pti.common.time.BusinessClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A {@link BusinessClock} that only moves when told to (DOC-44 §5.3). The default instant is
 * 2026-09-29T21:20:00Z, 16:20 CDT on a Tuesday: afternoon peak, inside the mini feed's window.
 */
public final class TestClock extends BusinessClock {

    public static final Instant DEFAULT = Instant.parse("2026-09-29T21:20:00Z");

    private final Manual manual;

    private TestClock(Manual manual) {
        super(manual, Duration.ZERO);
        this.manual = manual;
    }

    public static TestClock at(Instant instant) {
        return new TestClock(new Manual(instant));
    }

    public static TestClock atDefault() {
        return at(DEFAULT);
    }

    public TestClock advance(Duration duration) {
        manual.now = manual.now.plus(duration);
        return this;
    }

    public TestClock set(Instant instant) {
        manual.now = instant;
        return this;
    }

    private static final class Manual extends Clock {

        private volatile Instant now;

        Manual(Instant now) {
            this.now = now;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
