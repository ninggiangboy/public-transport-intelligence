package dev.pti.analytics.core.application.port;

import dev.pti.analytics.core.domain.Detector;

/** Counts the episodes that are still open, for the {@code pti_analytics_open_episodes} gauge (DOC-23 §14.1). */
public interface OpenEpisodeCounts {

    /**
     * @param detector {@code BUNCHING} or {@code DISRUPTION}, the detectors that keep episodes
     * @throws IllegalArgumentException for any other detector
     */
    long count(Detector detector);
}
