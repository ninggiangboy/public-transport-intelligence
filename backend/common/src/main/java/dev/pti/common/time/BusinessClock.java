package dev.pti.common.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * The business clock (DR-67, DOC-25 §3.1): real time shifted by {@code pti.clock.offset}, so that a demo run from
 * Vietnam lands on busy hours in Chicago. Business logic takes "now" from this clock only; infrastructure times
 * (Kafka record timestamps, audit columns, logs) stay on real time, available through {@link #realNow()}.
 */
public class BusinessClock extends Clock {

    /** The largest offset in either direction. */
    public static final Duration MAX_OFFSET = Duration.ofHours(24);

    private final Clock real;
    private final Duration offset;

    /**
     * @throws IllegalArgumentException when the offset is not a whole number of minutes or exceeds ±24 hours
     */
    public BusinessClock(Clock real, Duration offset) {
        // Validated before super() so that a rejected offset never leaves a partly built clock behind.
        checkOffset(offset);
        super();
        this.real = real.withZone(ZoneOffset.UTC);
        this.offset = offset;
    }

    private static void checkOffset(Duration offset) {
        if (offset.toSeconds() % 60 != 0 || offset.getNano() != 0) {
            throw new IllegalArgumentException("pti.clock.offset must be a whole number of minutes: " + offset);
        }
        if (offset.abs().compareTo(MAX_OFFSET) > 0) {
            throw new IllegalArgumentException("pti.clock.offset must be within ±24h: " + offset);
        }
    }

    /** Business time now. */
    @Override
    public Instant instant() {
        return real.instant().plus(offset);
    }

    /** Real time now, for Kafka timestamps, {@code produced_at} and audit columns. */
    public Instant realNow() {
        return real.instant();
    }

    /** The business time that corresponds to a real instant, e.g. a Kafka record timestamp. */
    public Instant businessTimeAt(Instant realInstant) {
        return realInstant.plus(offset);
    }

    public Duration offset() {
        return offset;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    /** Business clocks are always UTC; a zoned view would hide which of the two times is meant. */
    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("BusinessClock is always UTC");
    }
}
