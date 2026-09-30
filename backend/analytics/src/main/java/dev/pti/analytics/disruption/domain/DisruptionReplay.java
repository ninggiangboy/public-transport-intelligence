package dev.pti.analytics.disruption.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The bucket loop of DOC-23 §6.4, shared by the live detector and the recompute (§11.4): walk the buckets in order,
 * let the state machine process each direction that has not seen the bucket yet, and hand the state at every whole UTC
 * hour to a sink. The live detector starts from the stored state and the recompute from a snapshot; from there both run
 * this code, which is why they give the same episodes and the same state.
 */
public final class DisruptionReplay {

    /** The directions of a route (GTFS {@code direction_id}). */
    public static final List<Integer> DIRECTIONS = List.of(0, 1);

    private static final long SECONDS_PER_HOUR = 3600;

    private final DisruptionStateMachine machine;

    public DisruptionReplay(DisruptionThresholds thresholds) {
        this.machine = new DisruptionStateMachine(thresholds);
    }

    /** Receives the state of a direction right after the bucket that ends on a whole hour (DOC-23 §6.4). */
    @FunctionalInterface
    public interface SnapshotSink {
        void at(Instant snapshotHour, int directionId, BaselineState state);
    }

    /**
     * @param states the state of each direction before the first bucket
     * @param bucketEnds the ends of the buckets to process, oldest first
     * @param snapshots called for every direction that processed a bucket ending on a whole hour
     * @return the state after the last bucket and every change to an episode, oldest first
     */
    public Result run(
            String routeId,
            Map<Integer, DirectionState> states,
            ArrivalSeries arrivals,
            List<Instant> bucketEnds,
            SnapshotSink snapshots) {
        Map<Integer, DirectionState> current = new TreeMap<>(states);
        List<EpisodeChange> changes = new ArrayList<>();
        for (Instant end : bucketEnds) {
            boolean wholeHour = Math.floorMod(end.getEpochSecond(), SECONDS_PER_HOUR) == 0;
            for (int directionId : DIRECTIONS) {
                DirectionState state = current.get(directionId);
                if (!state.baseline().lastBucket().isBefore(end)) {
                    continue;
                }
                DisruptionStateMachine.Step step =
                        machine.process(routeId, directionId, state, arrivals.observe(directionId, end));
                current.put(directionId, step.state());
                if (step.change() != null) {
                    changes.add(step.change());
                }
                if (wholeHour) {
                    snapshots.at(end, directionId, step.state().baseline());
                }
            }
        }
        return new Result(current, changes);
    }

    /** The state of each direction after the last bucket and what the buckets did to the episodes. */
    public record Result(Map<Integer, DirectionState> states, List<EpisodeChange> changes) {}
}
