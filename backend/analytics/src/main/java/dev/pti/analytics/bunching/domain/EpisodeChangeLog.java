package dev.pti.analytics.bunching.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a run did to each episode, collected over its grid points. An episode that changes at several grid points is
 * written once, in its final state; one that opens and closes within the run is written closed but still reports the
 * moment it opened, for the alert and the event of the opening.
 */
public final class EpisodeChangeLog {

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    public void add(List<EpisodeChange> changes) {
        for (EpisodeChange change : changes) {
            BunchingEpisode episode = change.episode();
            Entry entry = entries.get(episode.id());
            boolean opened = change.kind() == EpisodeChange.Kind.OPENED;
            boolean closed = change.kind() == EpisodeChange.Kind.CLOSED;
            entries.put(
                    episode.id(),
                    new Entry(
                            opened ? episode : entry == null ? null : entry.opened(),
                            episode,
                            closed || (entry != null && entry.closed())));
        }
    }

    /** The episodes the run touched, in the order they first changed. */
    public List<Entry> entries() {
        return new ArrayList<>(entries.values());
    }

    /**
     * @param opened the episode as it was when it opened in this run; {@code null} when it was open already
     * @param latest the episode after the last change
     * @param closed whether it closed in this run
     */
    public record Entry(@Nullable BunchingEpisode opened, BunchingEpisode latest, boolean closed) {}
}
