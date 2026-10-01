package dev.pti.api.insight.application.port;

import dev.pti.api.insight.domain.DispatchSuggestion;
import dev.pti.api.insight.domain.Feedback;
import java.util.Optional;
import java.util.UUID;

/**
 * Where operator feedback on a dispatch suggestion is written and read back (DOC-32 E-18). Both go through the
 * primary: the row that was just written must not be looked up on a replica that may not have it yet (DOC-31 §10.1).
 */
public interface DispatchFeedbackStore {

    Optional<DispatchSuggestion> find(UUID id);

    /**
     * Records the feedback, replacing an earlier one (the last operator wins), and returns the row as it is now.
     *
     * @return empty when the suggestion does not exist (any more)
     */
    Optional<DispatchSuggestion> record(UUID id, Feedback feedback, String actor);
}
