package dev.pti.etl.rules;

import dev.pti.common.error.FatalException;
import dev.pti.etl.reference.ReferenceData;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Everything a rule may read besides the record itself, built once per chunk (DOC-16 §5).
 *
 * @param businessNow the business clock (DR-67), never the real time
 * @param replay true for raw-zone and dead-letter replays (DR-16): replay skips DQ-07, DQ-12 and the dedup registry
 * @param reference snapshot of the ACTIVE feed; absent before the first feed is loaded
 */
public record RuleContext(
        Instant businessNow, boolean replay, @Nullable ReferenceData reference) {

    /** The reference data a realtime rule needs; its absence is a configuration error, not a data error. */
    public ReferenceData requireReference() {
        if (reference == null) {
            throw new FatalException("No ACTIVE GTFS feed: realtime records cannot be validated yet");
        }
        return reference;
    }
}
