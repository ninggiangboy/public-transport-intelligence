package dev.pti.etl.rules;

import dev.pti.common.error.FatalException;
import dev.pti.etl.reference.ReferenceData;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Everything a rule may read besides the record itself, built once per chunk (DOC-16 §5).
 *
 * @param businessNow the business clock (DR-67), never the real time
 * @param clockOffset business time minus real time, to read a real instant such as {@code produced_at} as business time
 * @param replay true for raw-zone and dead-letter replays (DR-16): replay checks DQ-07 against the publication time
 *     and skips DQ-12 and the dedup registry (DR-100)
 * @param reference snapshot of the ACTIVE feed; absent before the first feed is loaded
 */
public record RuleContext(
        Instant businessNow,
        Duration clockOffset,
        boolean replay,
        @Nullable ReferenceData reference) {

    /** The reference data a realtime rule needs; its absence is a configuration error, not a data error. */
    public ReferenceData requireReference() {
        if (reference == null) {
            throw new FatalException("No ACTIVE GTFS feed: realtime records cannot be validated yet");
        }
        return reference;
    }
}
