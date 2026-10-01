package dev.pti.api.insight.application;

import dev.pti.api.insight.application.port.DispatchFeedbackStore;
import dev.pti.api.insight.domain.DispatchSuggestion;
import dev.pti.api.insight.domain.Feedback;
import dev.pti.api.platform.application.port.WriteMetrics;
import dev.pti.api.platform.domain.NotFoundException;
import dev.pti.common.tx.TransactionRunner;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code POST /insights/dispatch-suggestions/{id}/feedback} (DOC-32 E-18): an operator accepts or dismisses a
 * suggestion. A later answer replaces an earlier one (the last operator wins); the same answer from the same operator
 * is answered with the row as it is and writes nothing. No UI event: the suggestion list is refetched by the screen.
 */
public final class SubmitDispatchFeedback {

    private static final Logger log = LoggerFactory.getLogger(SubmitDispatchFeedback.class);

    private static final String OPERATION = "feedback";

    private final DispatchFeedbackStore store;
    private final WriteMetrics metrics;
    private final TransactionRunner tx;

    public SubmitDispatchFeedback(DispatchFeedbackStore store, WriteMetrics metrics, TransactionRunner tx) {
        this.store = store;
        this.metrics = metrics;
        this.tx = tx;
    }

    /**
     * @param actor {@code user:<username>} of the operator
     * @return the suggestion as it is after the call
     * @throws NotFoundException when there is no such suggestion
     */
    public DispatchSuggestion execute(String actor, UUID id, Feedback feedback) {
        Outcome outcome = tx.inTransaction(() -> apply(actor, id, feedback));
        metrics.recorded(OPERATION, outcome.recorded ? WriteMetrics.CREATED : WriteMetrics.IDEMPOTENT);
        if (outcome.recorded) {
            log.info("dispatch feedback recorded suggestionId={} feedback={} actor={}", id, feedback.wireName(), actor);
        }
        return outcome.suggestion;
    }

    private Outcome apply(String actor, UUID id, Feedback feedback) {
        DispatchSuggestion current = store.find(id).orElseThrow(SubmitDispatchFeedback::notFound);
        if (current.alreadyGiven(feedback, actor)) {
            return new Outcome(current, false);
        }
        return new Outcome(store.record(id, feedback, actor).orElseThrow(SubmitDispatchFeedback::notFound), true);
    }

    private static NotFoundException notFound() {
        return new NotFoundException("The dispatch suggestion does not exist.");
    }

    private record Outcome(DispatchSuggestion suggestion, boolean recorded) {}
}
