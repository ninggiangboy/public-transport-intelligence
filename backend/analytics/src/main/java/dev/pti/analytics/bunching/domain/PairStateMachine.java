package dev.pti.analytics.bunching.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The state machine of the pairs of one route (DOC-23 §5.5). It takes the evaluations of each grid point in order and
 * opens an episode when a pair has been below {@code open-ratio × headway} for {@code open-consecutive} evaluations in
 * a row, follows it while it is open and closes it when the gap recovers or the pair stops being evaluated.
 *
 * <p>Pure and mutable in one way only: it holds the current state of the route. It is built from the stored rows
 * ({@link #restore}) and read back after the last grid point ({@link #states()}).
 */
public final class PairStateMachine {

    private final String routeId;
    private final BunchingThresholds thresholds;
    private final Map<PairKey, PairState> states = new LinkedHashMap<>();
    private final Map<UUID, BunchingEpisode> openEpisodes = new LinkedHashMap<>();

    /** A machine with no state: every vehicle starts fresh. */
    public PairStateMachine(String routeId, BunchingThresholds thresholds) {
        this.routeId = routeId;
        this.thresholds = thresholds;
    }

    /**
     * A machine that continues from the stored rows of the route: the pair states and the episodes they have open. A
     * state row that points at an episode which is not among the open ones has nothing left to follow and is dropped.
     */
    public static PairStateMachine restore(
            String routeId,
            BunchingThresholds thresholds,
            Collection<PairState> states,
            Collection<BunchingEpisode> openEpisodes) {
        PairStateMachine machine = new PairStateMachine(routeId, thresholds);
        for (BunchingEpisode episode : openEpisodes) {
            if (episode.isOpen()) {
                machine.openEpisodes.put(episode.id(), episode);
            }
        }
        for (PairState state : states) {
            UUID episodeId = state.openEpisodeId();
            if (episodeId == null || machine.openEpisodes.containsKey(episodeId)) {
                machine.states.put(state.key(), state);
            }
        }
        return machine;
    }

    /** The state of every pair now. */
    public Map<PairKey, PairState> states() {
        return Map.copyOf(states);
    }

    public Optional<PairState> state(String leader, String follower) {
        return Optional.ofNullable(states.get(new PairKey(leader, follower)));
    }

    /** The episodes that are open now. */
    public Collection<BunchingEpisode> openEpisodes() {
        return List.copyOf(openEpisodes.values());
    }

    /**
     * Applies the evaluations of the next grid point.
     *
     * @return the episodes opened, updated and closed at this point, in the order it happened
     */
    public List<EpisodeChange> apply(TickEvaluation evaluation) {
        Instant tick = evaluation.tick();
        List<EpisodeChange> changes = new ArrayList<>();
        Set<PairKey> seen = new HashSet<>();
        for (Evaluation e : evaluation.evaluations()) {
            PairKey key = e.key();
            PairState state = states.get(key);
            if (state != null && !state.sameTrips(e)) {
                // The same two vehicles on other trips are a new pair.
                finish(state, CloseReason.PAIR_CHANGED, changes);
                state = null;
            }
            seen.add(key);
            if (state != null && state.isOpen()) {
                followOpen(key, state, e, tick, changes);
            } else if (e.gapSeconds() < thresholds.openRatio() * e.headwaySeconds()) {
                countBelow(key, state, e, tick, changes);
            } else {
                states.remove(key);
            }
        }
        for (PairState state : List.copyOf(states.values())) {
            if (seen.contains(state.key())) {
                continue;
            }
            if (state.isOpen()) {
                finish(state, reasonFor(state, evaluation), changes);
            } else {
                states.remove(state.key());
            }
        }
        return changes;
    }

    private void followOpen(PairKey key, PairState state, Evaluation e, Instant tick, List<EpisodeChange> changes) {
        BunchingEpisode episode = openEpisodes.get(state.openEpisodeId()).evaluated(e.gapSeconds(), tick);
        if (e.gapSeconds() > thresholds.closeRatio() * e.headwaySeconds()) {
            openEpisodes.remove(episode.id());
            states.remove(key);
            changes.add(new EpisodeChange(EpisodeChange.Kind.CLOSED, episode.closed(tick, CloseReason.GAP_RECOVERED)));
        } else {
            openEpisodes.put(episode.id(), episode);
            states.put(key, state.evaluatedAt(tick));
            changes.add(new EpisodeChange(EpisodeChange.Kind.UPDATED, episode));
        }
    }

    private void countBelow(PairKey key, PairState state, Evaluation e, Instant tick, List<EpisodeChange> changes) {
        PairState counting = (state == null ? PairState.beginning(e, tick) : state).countedBelow(e.gapSeconds(), tick);
        if (counting.consecutiveBelow() >= thresholds.openConsecutive()) {
            int threshold = Math.max(1, (int) Math.floor(thresholds.openRatio() * e.headwaySeconds()));
            BunchingEpisode episode = BunchingEpisode.open(routeId, counting, e, threshold, tick);
            openEpisodes.put(episode.id(), episode);
            counting = counting.withOpenEpisode(episode.id());
            changes.add(new EpisodeChange(EpisodeChange.Kind.OPENED, episode));
        }
        states.put(key, counting);
    }

    /** Closes the pair's episode, if it has one, at the last grid point at which the two vehicles were evaluated. */
    private void finish(PairState state, CloseReason reason, List<EpisodeChange> changes) {
        states.remove(state.key());
        if (state.isOpen()) {
            BunchingEpisode episode = openEpisodes.remove(state.openEpisodeId());
            if (episode != null) {
                changes.add(
                        new EpisodeChange(EpisodeChange.Kind.CLOSED, episode.closed(state.lastEvaluatedAt(), reason)));
            }
        }
    }

    /** Why a pair with an open episode was not evaluated at this grid point, by priority (DOC-23 §5.5). */
    private static CloseReason reasonFor(PairState state, TickEvaluation evaluation) {
        VehiclePosition leader = evaluation.active().get(state.leader());
        VehiclePosition follower = evaluation.active().get(state.follower());
        if (leader == null || follower == null) {
            return CloseReason.SIGNAL_LOST;
        }
        if (!leader.tripId().equals(state.leaderTrip()) || !follower.tripId().equals(state.followerTrip())) {
            return CloseReason.PAIR_CHANGED;
        }
        if (evaluation.evaluationOfFollower(state.follower()).isPresent()
                || evaluation.skipped().get(state.follower()) == SkipReason.NO_LEADER) {
            // The follower was evaluated with another leader (with this one the pair would have been seen), or has
            // no leader at all any more: another vehicle took the leader's place, or the two swapped.
            return CloseReason.PAIR_CHANGED;
        }
        return CloseReason.OUT_OF_ZONE;
    }
}
