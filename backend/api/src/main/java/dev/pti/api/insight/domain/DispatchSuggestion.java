package dev.pti.api.insight.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A dispatch suggestion for a bunching episode (DOC-32 E-17) and what an operator said about it. {@code
 * stateSnapshot} is the PII-free JSON the model was asked about, kept as text: parsing it is the web adapter's job.
 */
public record DispatchSuggestion(
        UUID id,
        UUID bunchingId,
        String routeId,
        String action,
        BigDecimal actionConfidence,
        String modelVersion,
        Instant createdAt,
        String stateSnapshot,
        @Nullable Feedback operatorFeedback,
        @Nullable String feedbackBy,
        @Nullable Instant feedbackAt) {

    /** FR-09.6: a suggestion below the threshold is shown as "Low confidence". */
    public boolean lowConfidence(BigDecimal threshold) {
        return actionConfidence.compareTo(threshold) < 0;
    }

    /** True when this operator has already given exactly this feedback, so saying it again changes nothing. */
    public boolean alreadyGiven(Feedback feedback, String actor) {
        return operatorFeedback == feedback && actor.equals(feedbackBy);
    }
}
