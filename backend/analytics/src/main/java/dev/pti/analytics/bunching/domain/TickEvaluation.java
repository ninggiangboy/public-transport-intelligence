package dev.pti.analytics.bunching.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the evaluator found at one grid point (DOC-23 §5.4): the evaluations, the vehicles that were active, and for
 * every active vehicle that was not evaluated as a follower the reason. The state machine needs all of it to tell why
 * a pair it had been following is no longer evaluated.
 *
 * @param active the newest position of each active vehicle, by vehicle id
 * @param skipped why an active vehicle was not evaluated as a follower, by vehicle id
 */
public record TickEvaluation(
        Instant tick,
        List<Evaluation> evaluations,
        Map<String, VehiclePosition> active,
        Map<String, SkipReason> skipped) {

    public TickEvaluation {
        evaluations = List.copyOf(evaluations);
        active = Collections.unmodifiableMap(new LinkedHashMap<>(active));
        skipped = Collections.unmodifiableMap(new LinkedHashMap<>(skipped));
    }

    /** The evaluation in which {@code follower} is the follower; there is at most one per vehicle. */
    public Optional<Evaluation> evaluationOfFollower(String follower) {
        return evaluations.stream().filter(e -> e.follower().equals(follower)).findFirst();
    }
}
