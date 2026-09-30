package dev.pti.common.events;

import java.util.Locale;

/**
 * The channel of a UI event (DOC-33 §2). On the wire (Kafka envelope, SSE) the name is lowercase; serialization is
 * the adapters' job, {@link #wireName()} and {@link #fromWireName(String)} only fix the spelling in one place.
 */
public enum UiChannel {
    VEHICLES,
    ALERTS,
    JOBS,
    DLQ;

    /** The lowercase name used in envelopes and in the SSE {@code channels} parameter. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * @throws IllegalArgumentException when the name is not a channel
     */
    public static UiChannel fromWireName(String wireName) {
        return valueOf(wireName.toUpperCase(Locale.ROOT));
    }
}
