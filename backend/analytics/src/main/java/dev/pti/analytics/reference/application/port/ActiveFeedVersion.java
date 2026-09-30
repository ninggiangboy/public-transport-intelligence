package dev.pti.analytics.reference.application.port;

import java.util.OptionalLong;

/**
 * The feed version that is ACTIVE now, as the app knows it. The reference cache compares it with the version it has
 * loaded and starts over when they differ; {@code etl} answers from its {@code ReferenceData} (DOC-21 §6.2), so the
 * check costs a memory read.
 */
public interface ActiveFeedVersion {

    /** Empty while no feed is ACTIVE. */
    OptionalLong current();
}
