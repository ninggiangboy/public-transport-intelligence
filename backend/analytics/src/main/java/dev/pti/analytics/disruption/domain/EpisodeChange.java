package dev.pti.analytics.disruption.domain;

/**
 * What one bucket did to an episode: it opened, it took new values, or it closed. The episode is as it is after the
 * bucket.
 */
public record EpisodeChange(Kind kind, DisruptionEpisode episode) {

    public enum Kind {
        OPENED,
        UPDATED,
        CLOSED
    }
}
