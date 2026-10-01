package dev.pti.api.insight.adapter.in.web;

import dev.pti.api.insight.domain.DispatchSuggestion;
import dev.pti.api.insight.domain.Feedback;
import dev.pti.api.platform.domain.ApiTime;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A dispatch suggestion (DOC-32 E-17, E-18, and inside E-11). {@code lowConfidence} is worked out against {@code
 * pti.api.dispatch.low-confidence}; the feedback members are absent until an operator has answered.
 */
public record DispatchSuggestionResponse(
        UUID id,
        UUID bunchingId,
        String routeId,
        String action,
        BigDecimal actionConfidence,
        boolean lowConfidence,
        String modelVersion,
        String createdAt,
        JsonNode stateSnapshot,
        @Nullable String operatorFeedback,
        @Nullable String feedbackBy,
        @Nullable String feedbackAt) {

    static DispatchSuggestionResponse from(DispatchSuggestion suggestion, BigDecimal lowConfidence, JsonMapper json) {
        Feedback feedback = suggestion.operatorFeedback();
        return new DispatchSuggestionResponse(
                suggestion.id(),
                suggestion.bunchingId(),
                suggestion.routeId(),
                suggestion.action(),
                suggestion.actionConfidence(),
                suggestion.lowConfidence(lowConfidence),
                suggestion.modelVersion(),
                ApiTime.format(suggestion.createdAt()),
                json.readTree(suggestion.stateSnapshot()),
                feedback == null ? null : feedback.wireName(),
                suggestion.feedbackBy(),
                ApiTime.formatNullable(suggestion.feedbackAt()));
    }
}
