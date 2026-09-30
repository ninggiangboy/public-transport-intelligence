package dev.pti.analytics.bunching.domain;

/**
 * An episode that the state machine opened, updated or closed at one grid point.
 *
 * @param episode the episode after the change
 */
public record EpisodeChange(Kind kind, BunchingEpisode episode) {

    /** What happened to the episode. */
    public enum Kind {
        /** A pair reached the required number of evaluations below the open ratio. */
        OPENED,
        /** An open episode was evaluated again and stays open. */
        UPDATED,
        /** The episode closed. */
        CLOSED
    }
}
