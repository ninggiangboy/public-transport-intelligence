package dev.pti.analytics.bunching.application.port;

import dev.pti.analytics.bunching.domain.BunchingEpisode;
import dev.pti.analytics.bunching.domain.PairState;
import dev.pti.analytics.bunching.domain.PairStateChanges;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * What the detector keeps in the database between runs (DOC-23 §5.5, §5.8): the cursor of each route and the state of
 * its pairs. The calls run in the transaction of the run that uses them, except the reads of the cursor and of the
 * routes that need the tick, which the fast path makes outside any write transaction.
 */
public interface BunchingStateStore {

    /** The last grid point evaluated for the route. */
    Optional<Instant> cursor(String routeId);

    /** Moves the cursor of the route to {@code lastTick}, a grid point. */
    void saveCursor(String routeId, Instant lastTick);

    /** The pair states of the route and the open episodes that those states follow. */
    StoredState load(String routeId);

    /** Writes the changed pair states and deletes the ones that are gone. */
    void save(String routeId, PairStateChanges changes);

    /** Routes with a pair state or an open episode, which need the idle tick (DOC-23 §4.2). */
    List<String> routesNeedingTick();

    /** The state of a route as stored. */
    record StoredState(List<PairState> pairStates, List<BunchingEpisode> openEpisodes) {

        public StoredState {
            pairStates = List.copyOf(pairStates);
            openEpisodes = List.copyOf(openEpisodes);
        }
    }
}
