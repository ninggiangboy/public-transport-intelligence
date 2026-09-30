package dev.pti.etl.analytics.adapter.out.feed;

import dev.pti.analytics.reference.application.port.ActiveFeedVersion;
import dev.pti.etl.reference.ReferenceData;
import dev.pti.etl.reference.ReferenceDataHolder;
import java.util.OptionalLong;

/**
 * The ACTIVE feed version as this JVM's {@link ReferenceDataHolder} knows it (DOC-21 §6.2). The holder follows the
 * database every 30 seconds, so analytics reloads its schedule data within that time of a feed change, and the check
 * itself is a memory read.
 */
public class ReferenceDataActiveFeed implements ActiveFeedVersion {

    private final ReferenceDataHolder holder;

    public ReferenceDataActiveFeed(ReferenceDataHolder holder) {
        this.holder = holder;
    }

    @Override
    public OptionalLong current() {
        return holder.current()
                .map(ReferenceData::feedVersionId)
                .map(OptionalLong::of)
                .orElseGet(OptionalLong::empty);
    }
}
